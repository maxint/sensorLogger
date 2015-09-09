/**
 * Copyright (C) 2012 Google Inc.
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.cellbots.logger.localServer;

import android.os.Environment;
import android.util.Log;

import org.apache.http.ConnectionClosedException;
import org.apache.http.HttpEntity;
import org.apache.http.HttpEntityEnclosingRequest;
import org.apache.http.HttpException;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.HttpServerConnection;
import org.apache.http.HttpStatus;
import org.apache.http.MethodNotSupportedException;
import org.apache.http.ProtocolVersion;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentProducer;
import org.apache.http.entity.EntityTemplate;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.DefaultHttpResponseFactory;
import org.apache.http.impl.DefaultHttpServerConnection;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.BasicHttpProcessor;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpService;
import org.apache.http.protocol.ResponseConnControl;
import org.apache.http.protocol.ResponseContent;
import org.apache.http.protocol.ResponseDate;
import org.apache.http.protocol.ResponseServer;
import org.apache.http.util.EntityUtils;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;

/**
 * This is the HTTP server that runs locally and listens on the specified port.
 * Accessing it directly will result in a dump of the current state of the
 * sensors. We plan to add other functionality such as serving out an HTML
 * interface and exposing logging controls through this local server. This code
 * is based off of
 * https://cellbots.googlecode.com/svn/trunk/android/java/cellbots/src/com/cellbots/httpserver/HttpCommandServer.java
 * originally created by chaitanyag@google.com (Chaitanya Gharpure).
 * 
 * @author clchen@google.com (Charles L. Chen)
 */
public class LocalHttpServer {

    private class ResponseResource<T> {
        public T resource;
        public String contentType;

        public ResponseResource(T res, String ct) {
            resource = res;
            contentType = ct;
        }
    }

    private class UrlParams {
        public String[] keys;

        public String[] values;

        public UrlParams(String url) {
            int index = url.indexOf('?');
            if (index >= 0) {
                String paramStr = url.substring(url.indexOf('?') + 1);
                String[] toks = paramStr.split("&");
                keys = new String[toks.length];
                values = new String[toks.length];
                int i = 0;
                for (String tok : toks) {
                    String[] tmp = tok.split("=");
                    if (tmp.length > 0) {
                        keys[i] = tmp[0];
                        if (tmp.length > 1)
                            values[i] = tmp[1];
                    } else {
                        keys[i] = tok;
                    }
                    i++;
                }
            }
        }
    }

    private static final String TAG = "LocalHttpServer";

    private static final String EXTERNAL_STORAGE_PATH =
            Environment.getExternalStorageDirectory() + "/";

    private int mPort = 8080;

    private String rootDir;

    private RequestListenerThread listenerThread;

    private boolean running = true;

    HashMap<String, ResponseResource<byte[]>> dataMap =
            new HashMap<String, ResponseResource<byte[]>>();

    HashMap<String, ResponseResource<String>> resourceMap =
            new HashMap<String, ResponseResource<String>>();

    private HttpCommandServerListener serverListener;

    public LocalHttpServer(String root, int port, HttpCommandServerListener listener) {
        serverListener = listener;
        mPort = port;
        setRoot(root);
        try {
            listenerThread = new RequestListenerThread(mPort);
            listenerThread.setDaemon(false);
            listenerThread.start();
        } catch (IOException e) {
            Log.e(TAG, "Error starting HTTP server: " + e.getMessage());
        }
    }

    public void setRoot(String root) {
        rootDir = EXTERNAL_STORAGE_PATH + root;
        File dir = new File(EXTERNAL_STORAGE_PATH + root);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    public void addResponseByName(String name, byte[] data, String contentType) {
        if (name == null || data == null)
            return;
        dataMap.put(name, new ResponseResource<byte[]>(data, contentType));
    }

    public void addResponseByName(String name, String path, String contentType) {
        if (name == null || path == null)
            return;
        resourceMap.put(name, new ResponseResource<String>(path, contentType));
    }

    public byte[] getResponseByName(String name) {
        return dataMap.containsKey(name) ? dataMap.get(name).resource : null;
    }

    public void stopServer() {
        running = false;
        if (listenerThread == null)
            return;
        listenerThread.stopServer();
        try {
            listenerThread.join();
        } catch (InterruptedException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    private String getResourceNameFromTarget(String target) {
        int lastPos = target.indexOf('?');
        return target.substring(1, lastPos >= 0 ? lastPos : target.length());
    }

    public void handle(final HttpServerConnection conn, final HttpContext context)
            throws HttpException, IOException {
        HttpRequest request = conn.receiveRequestHeader();
        HttpResponse response = new BasicHttpResponse(new ProtocolVersion("HTTP", 1, 1),
                HttpStatus.SC_OK, "OK");

        String method = request.getRequestLine().getMethod().toUpperCase(Locale.ENGLISH);
        if (!method.equals("GET") &&
                !method.equals("HEAD") &&
                !method.equals("POST") &&
                !method.equals("PUT")) {
            throw new MethodNotSupportedException(method + " method not supported");
        }

        // Get the requested target. This is the string after the domain name in
        // the URL. If the full URL was http://mydomain.com/test.html, target
        // will be /test.html.
        String target = request.getRequestLine().getUri();
        // Log.w(TAG, "*** Request target: " + target);

        // Gets the requested resource name. For example, if the full URL was
        // http://mydomain.com/test.html?x=1&y=2, resource name will be
        // test.html
        final String resName = getResourceNameFromTarget(target);
        UrlParams params = new UrlParams(target);
        // Log.w(TAG, "*** Request LINE: " +
        // request.getRequestLine().toString());
        // Log.w(TAG, "*** Request resource: " + resName);
        if (method.equals("POST") || method.equals("PUT")) {
            byte[] entityContent = null;
            // Gets the content if the request has an entity.
            if (request instanceof HttpEntityEnclosingRequest) {
                conn.receiveRequestEntity((HttpEntityEnclosingRequest) request);
                HttpEntity entity = ((HttpEntityEnclosingRequest) request).getEntity();
                if (entity != null) {
                    entityContent = EntityUtils.toByteArray(entity);
                }
            }
            response.setStatusCode(HttpStatus.SC_OK);
            if (serverListener != null) {
                serverListener.onRequest(resName, params.keys, params.values, entityContent);
            }
        } else if (dataMap.containsKey(resName)) { // The requested resource is
                                                   // a byte array
            response.setStatusCode(HttpStatus.SC_OK);
            response.setHeader("Content-Type", dataMap.get(resName).contentType);
            response.setEntity(new ByteArrayEntity(dataMap.get(resName).resource));
        } else { // Return sensor readings
            String contentType = resourceMap.containsKey(resName) ?
                    resourceMap.get(resName).contentType : "text/html";
            response.setStatusCode(HttpStatus.SC_OK);
            EntityTemplate body = new EntityTemplate(new ContentProducer() {
                    @Override
                public void writeTo(final OutputStream outstream) throws IOException {
                    OutputStreamWriter writer = new OutputStreamWriter(outstream, "UTF-8");
                    writer.write(serverListener.getLoggerStatus());
                    writer.flush();
                }
            });
            body.setContentType(contentType);
            response.setEntity(body);
        }
        conn.sendResponseHeader(response);
        conn.sendResponseEntity(response);
        conn.flush();
        conn.shutdown();
    }

    /**
     * This thread listens to HTTP requests and delegates it to worker threads.
     */
    class RequestListenerThread extends Thread {

        private final ServerSocket serversocket;
        private final HttpParams params;

        public RequestListenerThread(int port) throws IOException {
            this.serversocket = new ServerSocket(port);
            this.params = new BasicHttpParams();
            this.params
            .setIntParameter(CoreConnectionPNames.SO_TIMEOUT, 5000)
                    .setIntParameter(CoreConnectionPNames.SOCKET_BUFFER_SIZE, 8 * 1024)
                    .setBooleanParameter(CoreConnectionPNames.STALE_CONNECTION_CHECK, false)
                    .setBooleanParameter(CoreConnectionPNames.TCP_NODELAY, true)
                    .setParameter(CoreProtocolPNames.ORIGIN_SERVER, "HttpComponents/1.1");

            // Set up the HTTP protocol processor
            BasicHttpProcessor httpproc = new BasicHttpProcessor();
            httpproc.addInterceptor(new ResponseDate());
            httpproc.addInterceptor(new ResponseServer());
            httpproc.addInterceptor(new ResponseContent());
            httpproc.addInterceptor(new ResponseConnControl());

            // Set up the HTTP service
            new HttpService(httpproc, new DefaultConnectionReuseStrategy(),
                    new DefaultHttpResponseFactory());
        }

        public void stopServer() {
            try {
                this.serversocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void run() {
            Log.d(TAG, "*** Listening on port " + this.serversocket.getLocalPort());
            while (running) {
                try {
                    // Set up HTTP connection
                    Socket socket = this.serversocket.accept();
                    DefaultHttpServerConnection conn = new DefaultHttpServerConnection();
                    conn.bind(socket, this.params);
                    // Start worker thread
                    Thread t = new WorkerThread(conn);
                    t.setDaemon(true);
                    t.start();
                } catch (InterruptedIOException ex) {
                    break;
                } catch (IOException e) {
                    Log.e(TAG, "I/O error initialising connection thread: " + e.getMessage());
                    break;
                }
            }
            try {
                this.serversocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * This thread handles the HTTP requests.
     */
    class WorkerThread extends Thread {

        private final HttpServerConnection conn;

        public WorkerThread(final HttpServerConnection conn) {
            super();
            this.conn = conn;
        }

        @Override
        public void run() {
            HttpContext context = new BasicHttpContext(null);
            try {
                while (!Thread.interrupted() && this.conn.isOpen()) {
                    LocalHttpServer.this.handle(conn, context);
                }
            } catch (ConnectionClosedException ex) {
                Log.e(TAG, "Client closed connection");
            } catch (IOException ex) {
                ex.printStackTrace();
                Log.e(TAG, "I/O error: " + ex.getMessage());
            } catch (HttpException ex) {
                Log.e(TAG, "Unrecoverable HTTP protocol violation: " + ex.getMessage());
            } finally {
                try {
                    this.conn.shutdown();
                } catch (IOException ignore) {
                }
            }
        }
    }

    /**
     * Implement this interface to receive callback when an HTTP PUT/POST
     * request is received.
     */
    public interface HttpCommandServerListener {
        public void onRequest(String req, String[] keys, String[] values, byte[] data);

        public String getLoggerStatus();
    }

    /**
     * Returns the IP addresses of this device.
     * 
     * @return IP addresses as a String. Addresses are separated by newline.
     */
    public static String getLocalIpAddresses() {
        String ipAddresses = "";
        try {
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces();
                    en.hasMoreElements();) {
                NetworkInterface intf = en.nextElement();
                for (Enumeration<InetAddress> enumIpAddr = intf.getInetAddresses();
                        enumIpAddr.hasMoreElements();) {
                    InetAddress inetAddress = enumIpAddr.nextElement();
                    if (!inetAddress.isLoopbackAddress()) {
                        String ipAddress = inetAddress.getHostAddress().toString();
                        if (ipAddress.split("\\.").length == 4) {
                            ipAddresses = ipAddresses + ipAddress + ":8080\n";
                        }
                    }
                }
            }
        } catch (SocketException ex) {
            Log.e("", ex.toString());
        }
        return ipAddresses;
    }
}
