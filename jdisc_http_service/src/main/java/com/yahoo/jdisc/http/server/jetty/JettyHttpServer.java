// Copyright 2018 Yahoo Holdings. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.jdisc.http.server.jetty;

import com.google.inject.Inject;
import com.yahoo.component.ComponentId;
import com.yahoo.component.provider.ComponentRegistry;
import com.yahoo.concurrent.DaemonThreadFactory;
import com.yahoo.container.logging.AccessLog;
import com.yahoo.jdisc.Metric;
import com.yahoo.jdisc.http.ConnectorConfig;
import com.yahoo.jdisc.http.ServerConfig;
import com.yahoo.jdisc.http.ServletPathsConfig;
import com.yahoo.jdisc.http.server.FilterBindings;
import com.yahoo.jdisc.service.AbstractServerProvider;
import com.yahoo.jdisc.service.CurrentContainer;
import org.eclipse.jetty.http.HttpField;
import org.eclipse.jetty.io.ConnectionStatistics;
import org.eclipse.jetty.jmx.ConnectorServer;
import org.eclipse.jetty.jmx.MBeanContainer;
import org.eclipse.jetty.server.Connector;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.server.SslConnectionFactory;
import org.eclipse.jetty.server.handler.AbstractHandlerContainer;
import org.eclipse.jetty.server.handler.HandlerCollection;
import org.eclipse.jetty.server.handler.StatisticsHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHandler;
import org.eclipse.jetty.server.handler.gzip.GzipHttpOutputInterceptor;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.JavaUtilLog;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.thread.QueuedThreadPool;

import javax.management.remote.JMXServiceURL;
import javax.servlet.DispatcherType;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.BindException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.toList;

/**
 * @author Simon Thoresen Hult
 * @author bjorncs
 */
public class JettyHttpServer extends AbstractServerProvider {

    private final static Logger log = Logger.getLogger(JettyHttpServer.class.getName());
    private final long timeStarted = System.currentTimeMillis();
    private final ExecutorService janitor;
    private final ScheduledExecutorService metricReporterExecutor;
    private final Metric metric;
    private final Server server;
    private final List<Integer> listenedPorts = new ArrayList<>();

    @Inject
    public JettyHttpServer(CurrentContainer container,
                           Metric metric,
                           ServerConfig serverConfig,
                           ServletPathsConfig servletPathsConfig,
                           FilterBindings filterBindings,
                           ComponentRegistry<ConnectorFactory> connectorFactories,
                           ComponentRegistry<ServletHolder> servletHolders,
                           FilterInvoker filterInvoker,
                           AccessLog accessLog) {
        super(container);
        if (connectorFactories.allComponents().isEmpty())
            throw new IllegalArgumentException("No connectors configured.");
        this.metric = metric;

        initializeJettyLogging();

        server = new Server();
        server.setStopTimeout((long)(serverConfig.stopTimeout() * 1000.0));
        server.setRequestLog(new AccessLogRequestLog(accessLog));
        setupJmx(server, serverConfig);
        ((QueuedThreadPool)server.getThreadPool()).setMaxThreads(serverConfig.maxWorkerThreads());

        for (ConnectorFactory connectorFactory : connectorFactories.allComponents()) {
            ConnectorConfig connectorConfig = connectorFactory.getConnectorConfig();
            server.addConnector(connectorFactory.createConnector(metric, server));
            listenedPorts.add(connectorConfig.listenPort());
        }

        janitor = newJanitor();

        JDiscContext jDiscContext = new JDiscContext(filterBindings.getRequestFilters().activate(),
                                                     filterBindings.getResponseFilters().activate(),
                                                     container,
                                                     janitor,
                                                     metric,
                                                     serverConfig);

        ServletHolder jdiscServlet = new ServletHolder(new JDiscHttpServlet(jDiscContext));
        FilterHolder jDiscFilterInvokerFilter = new FilterHolder(new JDiscFilterInvokerFilter(jDiscContext, filterInvoker));

        List<JDiscServerConnector> connectors = Arrays.stream(server.getConnectors())
                                                      .map(JDiscServerConnector.class::cast)
                                                      .collect(toList());

        server.setHandler(getHandlerCollection(serverConfig,
                                               servletPathsConfig,
                                               connectors,
                                               jdiscServlet,
                                               servletHolders,
                                               jDiscFilterInvokerFilter));

        int numMetricReporterThreads = 1;
        metricReporterExecutor =
                Executors.newScheduledThreadPool(numMetricReporterThreads,
                        new DaemonThreadFactory(JettyHttpServer.class.getName() + "-MetricReporter-"));
        metricReporterExecutor.scheduleAtFixedRate(new MetricTask(), 0, 2, TimeUnit.SECONDS);
    }

    private static void initializeJettyLogging() {
        // Note: Jetty is logging stderr if no logger is explicitly configured
        try {
            Log.setLog(new JavaUtilLog());
        } catch (Exception e) {
            throw new RuntimeException("Unable to initialize logging framework for Jetty");
        }
    }

    private static void setupJmx(Server server, ServerConfig serverConfig) {
        if (serverConfig.jmx().enabled()) {
            System.setProperty("java.rmi.server.hostname", "localhost");
            server.addBean(new MBeanContainer(ManagementFactory.getPlatformMBeanServer()));
            server.addBean(new ConnectorServer(createJmxLoopbackOnlyServiceUrl(serverConfig.jmx().listenPort()),
                                               "org.eclipse.jetty.jmx:name=rmiconnectorserver"));
        }
    }

    private static JMXServiceURL createJmxLoopbackOnlyServiceUrl(int port) {
        try {
            return new JMXServiceURL("rmi", "localhost", port, "/jndi/rmi://localhost:" + port + "/jmxrmi");
        } catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private HandlerCollection getHandlerCollection(ServerConfig serverConfig,
                                                   ServletPathsConfig servletPathsConfig,
                                                   List<JDiscServerConnector> connectors,
                                                   ServletHolder jdiscServlet,
                                                   ComponentRegistry<ServletHolder> servletHolders,
                                                   FilterHolder jDiscFilterInvokerFilter) {
        ServletContextHandler servletContextHandler = createServletContextHandler();

        servletHolders.allComponentsById().forEach((id, servlet) -> {
            String path = getServletPath(servletPathsConfig, id);
            servletContextHandler.addServlet(servlet, path);
            servletContextHandler.addFilter(jDiscFilterInvokerFilter, path, EnumSet.allOf(DispatcherType.class));
        });

        servletContextHandler.addServlet(jdiscServlet, "/*");

        List<ConnectorConfig> connectorConfigs = connectors.stream().map(JDiscServerConnector::connectorConfig).collect(toList());
        var secureRedirectHandler = new SecuredRedirectHandler(connectorConfigs);
        secureRedirectHandler.setHandler(servletContextHandler);

        var proxyHandler = new HealthCheckProxyHandler(connectors);
        proxyHandler.setHandler(secureRedirectHandler);

        var authEnforcer = new TlsClientAuthenticationEnforcer(connectorConfigs);
        authEnforcer.setHandler(proxyHandler);

        GzipHandler gzipHandler = newGzipHandler(serverConfig);
        gzipHandler.setHandler(authEnforcer);

        HttpResponseStatisticsCollector statisticsCollector =
                new HttpResponseStatisticsCollector(serverConfig.metric().monitoringHandlerPaths(),
                                                    serverConfig.metric().searchHandlerPaths());
        statisticsCollector.setHandler(gzipHandler);

        StatisticsHandler statisticsHandler = newStatisticsHandler();
        statisticsHandler.setHandler(statisticsCollector);

        HandlerCollection handlerCollection = new HandlerCollection();
        handlerCollection.setHandlers(new Handler[] { statisticsHandler });
        return handlerCollection;
    }

    private static String getServletPath(ServletPathsConfig servletPathsConfig, ComponentId id) {
        return "/" + servletPathsConfig.servlets(id.stringValue()).path();
    }

    private ServletContextHandler createServletContextHandler() {
        ServletContextHandler servletContextHandler = new ServletContextHandler(ServletContextHandler.NO_SECURITY | ServletContextHandler.NO_SESSIONS);
        servletContextHandler.setContextPath("/");
        servletContextHandler.setDisplayName(getDisplayName(listenedPorts));
        return servletContextHandler;
    }

    private static String getDisplayName(List<Integer> ports) {
        return ports.stream().map(Object::toString).collect(Collectors.joining(":"));
    }

    private static ExecutorService newJanitor() {
        int threadPoolSize = Runtime.getRuntime().availableProcessors();
        log.info("Creating janitor executor with " + threadPoolSize + " threads");
        return Executors.newFixedThreadPool(
                threadPoolSize,
                new DaemonThreadFactory(JettyHttpServer.class.getName() + "-Janitor-"));
    }

    @Override
    public void start() {
        try {
            server.start();
            logEffectiveSslConfiguration();
        } catch (final Exception e) {
            if (e instanceof IOException && e.getCause() instanceof BindException) {
                throw new RuntimeException("Failed to start server due to BindExecption. ListenPorts = " + listenedPorts.toString(), e.getCause());
            }
            throw new RuntimeException("Failed to start server.", e);
        }
    }

    private void logEffectiveSslConfiguration() {
        if (!server.isStarted()) throw new IllegalStateException();
        for (Connector connector : server.getConnectors()) {
            ServerConnector serverConnector = (ServerConnector) connector;
            int localPort = serverConnector.getLocalPort();
            var sslConnectionFactory = serverConnector.getConnectionFactory(SslConnectionFactory.class);
            if (sslConnectionFactory != null) {
                var sslContextFactory = sslConnectionFactory.getSslContextFactory();
                log.info(String.format("Enabled SSL cipher suites for port '%d': %s",
                                       localPort, Arrays.toString(sslContextFactory.getSelectedCipherSuites())));
                log.info(String.format("Enabled SSL protocols for port '%d': %s",
                                       localPort, Arrays.toString(sslContextFactory.getSelectedProtocols())));
            }
        }
    }

    @Override
    public void close() {
        try {
            log.log(Level.INFO, String.format("Shutting down server (graceful=%b, timeout=%.1fs)", isGracefulShutdownEnabled(), server.getStopTimeout()/1000d));
            server.stop();
            log.log(Level.INFO, "Server shutdown completed");
        } catch (final Exception e) {
            log.log(Level.SEVERE, "Server shutdown threw an unexpected exception.", e);
        }

        metricReporterExecutor.shutdown();
        janitor.shutdown();
    }

    private boolean isGracefulShutdownEnabled() {
        return server.getChildHandlersByClass(StatisticsHandler.class).length > 0 && server.getStopTimeout() > 0;
    }

    public int getListenPort() {
        return ((ServerConnector)server.getConnectors()[0]).getLocalPort();
    }

    Server server() { return server; }

    private class MetricTask implements Runnable {
        @Override
        public void run() {
            HttpResponseStatisticsCollector statisticsCollector = ((AbstractHandlerContainer) server.getHandler())
                    .getChildHandlerByClass(HttpResponseStatisticsCollector.class);
            if (statisticsCollector != null) {
                setServerMetrics(statisticsCollector);
            }

            // reset statisticsHandler to preserve earlier behavior
            StatisticsHandler statisticsHandler = ((AbstractHandlerContainer) server.getHandler())
                    .getChildHandlerByClass(StatisticsHandler.class);
            if (statisticsHandler != null) {
                statisticsHandler.statsReset();
            }

            for (Connector connector : server.getConnectors()) {
                setConnectorMetrics((JDiscServerConnector)connector);
            }
        }

    }

    private void setServerMetrics(HttpResponseStatisticsCollector statisticsCollector) {
        long timeSinceStarted = System.currentTimeMillis() - timeStarted;
        metric.set(MetricDefinitions.STARTED_MILLIS, timeSinceStarted, null);

        addResponseMetrics(statisticsCollector);
    }

    private void addResponseMetrics(HttpResponseStatisticsCollector statisticsCollector) {
        for (var metricEntry : statisticsCollector.takeStatistics()) {
            Map<String, Object> dimensions = new HashMap<>();
            dimensions.put(MetricDefinitions.METHOD_DIMENSION, metricEntry.method);
            dimensions.put(MetricDefinitions.SCHEME_DIMENSION, metricEntry.scheme);
            dimensions.put(MetricDefinitions.REQUEST_TYPE_DIMENSION, metricEntry.requestType);
            metric.add(metricEntry.name, metricEntry.value, metric.createContext(dimensions));
        }
    }

    private void setConnectorMetrics(JDiscServerConnector connector) {
        ConnectionStatistics statistics = connector.getStatistics();
        metric.set(MetricDefinitions.NUM_CONNECTIONS, statistics.getConnectionsTotal(), connector.getConnectorMetricContext());
        metric.set(MetricDefinitions.NUM_OPEN_CONNECTIONS, statistics.getConnections(), connector.getConnectorMetricContext());
        metric.set(MetricDefinitions.NUM_CONNECTIONS_OPEN_MAX, statistics.getConnectionsMax(), connector.getConnectorMetricContext());
        metric.set(MetricDefinitions.CONNECTION_DURATION_MAX, statistics.getConnectionDurationMax(), connector.getConnectorMetricContext());
        metric.set(MetricDefinitions.CONNECTION_DURATION_MEAN, statistics.getConnectionDurationMean(), connector.getConnectorMetricContext());
        metric.set(MetricDefinitions.CONNECTION_DURATION_STD_DEV, statistics.getConnectionDurationStdDev(), connector.getConnectorMetricContext());
    }

    private StatisticsHandler newStatisticsHandler() {
        StatisticsHandler statisticsHandler = new StatisticsHandler();
        statisticsHandler.statsReset();
        return statisticsHandler;
    }

    private GzipHandler newGzipHandler(ServerConfig serverConfig) {
        GzipHandler gzipHandler = new GzipHandlerWithVaryHeaderFixed();
        gzipHandler.setCompressionLevel(serverConfig.responseCompressionLevel());
        gzipHandler.setInflateBufferSize(8 * 1024);
        gzipHandler.setIncludedMethods("GET", "POST", "PUT", "PATCH");
        return gzipHandler;
    }

    /** A subclass which overrides Jetty's default behavior of including user-agent in the vary field */
    private static class GzipHandlerWithVaryHeaderFixed extends GzipHandler {

        @Override
        public HttpField getVaryField() {
            return GzipHttpOutputInterceptor.VARY_ACCEPT_ENCODING;
        }

    }

}
