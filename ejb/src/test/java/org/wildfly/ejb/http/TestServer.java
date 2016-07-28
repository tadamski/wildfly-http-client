package org.wildfly.ejb.http;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jboss.ejb.client.SessionID;
import org.jboss.ejb.client.StatefulEJBLocator;
import org.jboss.marshalling.ByteOutput;
import org.jboss.marshalling.InputStreamByteInput;
import org.jboss.marshalling.Marshaller;
import org.jboss.marshalling.MarshallerFactory;
import org.jboss.marshalling.Marshalling;
import org.jboss.marshalling.MarshallingConfiguration;
import org.jboss.marshalling.Unmarshaller;
import org.jboss.marshalling.river.RiverMarshallerFactory;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.RunListener;
import org.junit.runner.notification.RunNotifier;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;
import org.xnio.OptionMap;
import org.xnio.Options;
import org.xnio.Xnio;
import org.xnio.XnioWorker;
import io.undertow.Undertow;
import io.undertow.connector.ByteBufferPool;
import io.undertow.server.DefaultByteBufferPool;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.Cookie;
import io.undertow.server.handlers.CookieImpl;
import io.undertow.server.handlers.PathHandler;
import io.undertow.testutils.DebuggingSlicePool;
import io.undertow.testutils.DefaultServer;
import io.undertow.util.Headers;
import io.undertow.util.NetworkUtils;

/**
 * @author Stuart Douglas
 */
public class TestServer extends BlockJUnit4ClassRunner {


    private static final String JSESSIONID = "JSESSIONID";
    public static final int BUFFER_SIZE = Integer.getInteger("test.bufferSize", 8192 * 3);
    private static final PathHandler PATH_HANDLER = new PathHandler();
    public static final String SFSB_ID = "SFSB_ID";
    public static final String WILDFLY_SERVICES = "/wildfly-services";
    public static final String INITIAL_SESSION_AFFINITY = "initial-session-affinity";
    private static boolean first = true;
    private static Undertow undertow;

    private static XnioWorker worker;
    private static final MarshallerFactory marshallerFactory = new RiverMarshallerFactory();

    private static final DebuggingSlicePool pool = new DebuggingSlicePool(new DefaultByteBufferPool(true, BUFFER_SIZE, 1000, 10, 100));

    private static volatile TestEJBHandler handler;

    private static final Set<String> registeredPaths = new HashSet<>();

    public static TestEJBHandler getHandler() {
        return handler;
    }

    public static void setHandler(TestEJBHandler handler) {
        TestServer.handler = handler;
    }

    /**
     * @return The base URL that can be used to make connections to this server
     */
    public static String getDefaultServerURL() {
        return getDefaultRootServerURL() + WILDFLY_SERVICES;
    }

    public static String getDefaultRootServerURL() {
        return "http://" + NetworkUtils.formatPossibleIpv6Address(getHostAddress()) + ":" + getHostPort();
    }

    public static InetSocketAddress getDefaultServerAddress() {
        return new InetSocketAddress(DefaultServer.getHostAddress("default"), DefaultServer.getHostPort("default"));
    }

    public TestServer(Class<?> klass) throws InitializationError {
        super(klass);
    }

    public static ByteBufferPool getBufferPool() {
        return pool;
    }

    @Override
    public Description getDescription() {
        return super.getDescription();
    }

    @Override
    public void run(final RunNotifier notifier) {
        runInternal(notifier);
        notifier.addListener(new RunListener() {
            @Override
            public void testFinished(Description description) throws Exception {
                for (String reg : registeredPaths) {
                    PATH_HANDLER.removePrefixPath(reg);
                }
                registeredPaths.clear();
            }
        });
        super.run(notifier);
    }

    public static void registerPathHandler(String path, HttpHandler handler) {
        PATH_HANDLER.addPrefixPath(path, handler);
        registeredPaths.add(path);
    }

    public static XnioWorker getWorker() {
        return worker;
    }

    private static void runInternal(final RunNotifier notifier) {
        try {
            if (first) {
                first = false;
                Xnio xnio = Xnio.getInstance("nio");
                worker = xnio.createWorker(OptionMap.create(Options.WORKER_TASK_CORE_THREADS, 20, Options.WORKER_IO_THREADS, 10));
                PathHandler servicesHandler = new PathHandler();
                servicesHandler.addPrefixPath("/ejb", new TestEJBHTTPHandler());
                undertow = Undertow.builder()
                        .addHttpListener(getHostPort(), getHostAddress())
                        .setHandler(PATH_HANDLER.addPrefixPath("/wildfly-services", servicesHandler))
                        .build();
                undertow.start();
                notifier.addListener(new RunListener() {
                    @Override
                    public void testRunFinished(final Result result) throws Exception {
                        undertow.stop();
                    }
                });
            }
        } catch (Exception e) {
            throw new RuntimeException();
        }
    }


    public static String getHostAddress() {
        return System.getProperty("server.address", "localhost");
    }

    public static int getHostPort() {
        return Integer.getInteger("server.port", 7788);
    }

    private static final class TestEJBHTTPHandler implements HttpHandler {

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            if (exchange.isInIoThread()) {
                exchange.dispatch(this);
                return;
            }
            exchange.startBlocking();
            System.out.println(exchange.getRelativePath());
            String relativePath = exchange.getRelativePath();
            if (relativePath.startsWith("/")) {
                relativePath = relativePath.substring(1);
            }
            String[] parts = relativePath.split("/");
            String content = exchange.getRequestHeaders().getFirst(Headers.CONTENT_TYPE);
            switch (content) {
                case EjbHeaders.INVOCATION_VERSION_ONE:
                    handleInvocation(parts, exchange);
                    break;
                case EjbHeaders.SESSION_CREATE_VERSION_ONE:
                    handleSessionCreate(parts, exchange);
                    break;
                case EjbHeaders.AFFINITY_VERSION_ONE:
                    handleAffinity(parts, exchange);
                    break;
                default:
                    sendException(exchange, 400, new RuntimeException("Unknown content type " + content));
                    return;
            }

        }

        private void handleAffinity(String[] parts, HttpServerExchange exchange) {
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, EjbHeaders.EJB_RESPONSE_AFFINITY_RESULT_VERSION_ONE);
            exchange.getResponseCookies().put("JSESSIONID", new CookieImpl("JSESSIONID", INITIAL_SESSION_AFFINITY).setPath(WILDFLY_SERVICES));
        }

        private void handleSessionCreate(String[] parts, HttpServerExchange exchange) throws Exception {

            if (parts.length < 5) {
                sendException(exchange, 400, new RuntimeException("not enough URL segments " + exchange.getRelativePath()));
                return;
            }

            String app = handleDash(parts[0]);
            String module = handleDash(parts[1]);
            String distict = handleDash(parts[2]);
            String bean = parts[3];
            Class<?> view = Class.forName(parts[4]);

            final MarshallingConfiguration marshallingConfiguration = new MarshallingConfiguration();
            marshallingConfiguration.setClassTable(ProtocolV1ClassTable.INSTANCE);
            marshallingConfiguration.setObjectTable(ProtocolV1ObjectTable.INSTANCE);
            marshallingConfiguration.setVersion(2);
            SessionID sessionID = SessionID.createSessionID(SFSB_ID.getBytes(StandardCharsets.US_ASCII));
            StatefulEJBLocator locator = new StatefulEJBLocator(view, app, module, bean, distict, sessionID, null, "test");


            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, EjbHeaders.EJB_RESPONSE_NEW_SESSION);

            final Marshaller marshaller = marshallerFactory.createMarshaller(marshallingConfiguration);
            OutputStream outputStream = exchange.getOutputStream();
            final ByteOutput byteOutput = Marshalling.createByteOutput(outputStream);
            // start the marshaller
            marshaller.start(byteOutput);
            marshaller.writeObject(locator);
            marshaller.finish();
            marshaller.flush();

        }

        private void handleInvocation(String[] parts, HttpServerExchange exchange) throws Exception {

            if (parts.length < 7) {
                sendException(exchange, 400, new RuntimeException("not enough URL segments " + exchange.getRelativePath()));
                return;
            }

            String app = handleDash(parts[0]);
            String module = handleDash(parts[1]);
            String distict = handleDash(parts[2]);
            String bean = parts[3];
            String sessionID = parts[4];
            Class<?> view = Class.forName(parts[5]);
            String method = parts[6];
            Class[] paramTypes = new Class[parts.length - 7];
            for (int i = 7; i < parts.length; ++i) {
                paramTypes[i - 7] = Class.forName(parts[i]);
            }
            Object[] params = new Object[paramTypes.length];

            final MarshallingConfiguration marshallingConfiguration = new MarshallingConfiguration();
            marshallingConfiguration.setClassTable(ProtocolV1ClassTable.INSTANCE);
            marshallingConfiguration.setObjectTable(ProtocolV1ObjectTable.INSTANCE);
            marshallingConfiguration.setVersion(2);
            Unmarshaller unmarshaller = marshallerFactory.createUnmarshaller(marshallingConfiguration);

            unmarshaller.start(new InputStreamByteInput(exchange.getInputStream()));
            for (int i = 0; i < paramTypes.length; ++i) {
                params[i] = unmarshaller.readObject();
            }
            final Map<?, ?> privateAttachments;
            final Map<String, Object> contextData;
            int attachementCount = PackedInteger.readPackedInteger(unmarshaller);
            if (attachementCount > 0) {
                contextData = new HashMap<>();
                for (int i = 0; i < attachementCount - 1; ++i) {
                    String key = (String) unmarshaller.readObject();
                    Object value = unmarshaller.readObject();
                    contextData.put(key, value);
                }
                privateAttachments = (Map<?, ?>) unmarshaller.readObject();
            } else {
                contextData = Collections.emptyMap();
                privateAttachments = Collections.emptyMap();
            }

            unmarshaller.finish();
            Cookie cookie = exchange.getRequestCookies().get(JSESSIONID);
            String sessionAffinity = null;
            if (cookie != null) {
                sessionAffinity = cookie.getValue();
            }


            TestEJBInvocation invocation = new TestEJBInvocation(app, module, distict, bean, sessionID, sessionAffinity, view, method, paramTypes, params, privateAttachments, contextData);

            try {
                TestEjbOutput output = new TestEjbOutput();
                Object result = handler.handle(invocation, output);
                exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, EjbHeaders.EJB_RESPONSE_VERSION_ONE);
                if (output.getSessionAffinity() != null) {
                    exchange.getResponseCookies().put("JSESSIONID", new CookieImpl("JSESSIONID", output.getSessionAffinity()).setPath(WILDFLY_SERVICES));
                }
                final Marshaller marshaller = marshallerFactory.createMarshaller(marshallingConfiguration);
                OutputStream outputStream = exchange.getOutputStream();
                final ByteOutput byteOutput = Marshalling.createByteOutput(outputStream);
                // start the marshaller
                marshaller.start(byteOutput);
                marshaller.writeObject(result);
                marshaller.write(0);
                marshaller.finish();
                marshaller.flush();

            } catch (Exception e) {
                sendException(exchange, 500, e);
            }

        }
    }

    private static void sendException(HttpServerExchange exchange, int status, Exception e) throws IOException {
        exchange.setStatusCode(status);
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, EjbHeaders.EJB_RESPONSE_EXCEPTION_VERSION_ONE);

        final MarshallingConfiguration marshallingConfiguration = new MarshallingConfiguration();
        marshallingConfiguration.setClassTable(ProtocolV1ClassTable.INSTANCE);
        marshallingConfiguration.setObjectTable(ProtocolV1ObjectTable.INSTANCE);
        marshallingConfiguration.setVersion(2);
        final Marshaller marshaller = marshallerFactory.createMarshaller(marshallingConfiguration);
        OutputStream outputStream = exchange.getOutputStream();
        final ByteOutput byteOutput = Marshalling.createByteOutput(outputStream);
        // start the marshaller
        marshaller.start(byteOutput);
        marshaller.writeObject(e);
        marshaller.write(0);
        marshaller.finish();
        marshaller.flush();
    }


    private static String handleDash(String s) {
        if(s.equals("-")) {
            return "";
        }
        return s;
    }
}
