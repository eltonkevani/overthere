package com.xebialabs.overthere.cifs.winrm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URL;
import java.nio.charset.Charset;
import java.security.KeyManagementException;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivilegedActionException;
import java.security.PrivilegedExceptionAction;
import java.security.UnrecoverableKeyException;

import javax.security.auth.Subject;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.kerberos.KerberosPrincipal;
import javax.security.auth.login.LoginContext;
import javax.security.auth.login.LoginException;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.BasicUserPrincipal;
import org.apache.http.auth.Credentials;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.TrustStrategy;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.entity.ByteArrayEntity;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.StringEntity;
import org.apache.http.entity.mime.MultipartEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.protocol.BasicHttpContext;
import org.apache.http.protocol.HttpContext;
import org.apache.http.util.EntityUtils;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.io.OutputFormat;
import org.dom4j.io.XMLWriter;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.MessageProp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.io.Closeables;
import com.google.common.primitives.Bytes;

import com.xebialabs.overthere.cifs.WinrmHttpsCertificateTrustStrategy;
import com.xebialabs.overthere.cifs.WinrmHttpsHostnameVerificationStrategy;
import com.xebialabs.overthere.cifs.winrm.soap.SoapAction;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static org.apache.http.auth.AuthScope.ANY_HOST;
import static org.apache.http.auth.AuthScope.ANY_PORT;
import static org.apache.http.auth.AuthScope.ANY_REALM;
import static org.apache.http.client.params.AuthPolicy.BASIC;
import static org.apache.http.client.params.AuthPolicy.KERBEROS;
import static org.apache.http.client.params.AuthPolicy.SPNEGO;
import static org.apache.http.client.params.ClientPNames.HANDLE_AUTHENTICATION;

class WinRmHttpClient {
    // Configuration options set in constructor
    private final boolean enableKerberos;
    private final String username;
    private final String password;
    private final URL targetURL;
    private final String unmappedAddress;
    private final int unmappedPort;

    private WinrmHttpsCertificateTrustStrategy httpsCertTrustStrategy;
    private WinrmHttpsHostnameVerificationStrategy httpsHostnameVerifyStrategy;
    private boolean kerberosUseHttpSpn;
    private boolean kerberosAddPortToSpn;
    private boolean kerberosDebug;

    // HTTP and Kerberos State
    private DefaultHttpClient httpClient;
    private HttpContext httpContext;
    private LoginContext kerberosLoginContext;
    private GSSContext kerberosGSSContext;

    WinRmHttpClient(final String username, final String password, final URL targetURL, final String unmappedAddress, final int unmappedPort) {
        int posOfAtSign = username.indexOf('@');
        if (posOfAtSign >= 0) {
            String u = username.substring(0, posOfAtSign);
            String d = username.substring(posOfAtSign + 1);
            if (d.toUpperCase().equals(d)) {
                this.username = username;
            } else {
                this.username = u + "@" + d.toUpperCase();
                logger.warn("Fixing username [{}] to have an upper case domain name [{}]", username, this.username);
            }
            this.enableKerberos = true;
        } else {
            this.username = username;
            this.enableKerberos = false;
        }
        this.password = password;
        this.targetURL = targetURL;
        this.unmappedAddress = unmappedAddress;
        this.unmappedPort = unmappedPort;
    }

    void connect() {
        logger.trace("Connecting to [{}]", targetURL);
        httpClient = new DefaultHttpClient();

        try {
            configureTrust();
            configureAuthentication(BASIC, new BasicUserPrincipal(username));

            if (enableKerberos) {
                checkState(kerberosLoginContext == null, "kerberosLoginContext != null: Invoke connect() exactly once");

                logger.debug("Authenticating to Kerberos KDC with username [{}]", username);
                final CallbackHandler handler = new ProvidedAuthCallback(username, password);
                kerberosLoginContext = new LoginContext("", null, handler, new KerberosJaasConfiguration(kerberosDebug));
                kerberosLoginContext.login();

                String spnServiceClass = kerberosUseHttpSpn ? "HTTP" : "WSMAN";
                httpClient.getAuthSchemes().register(KERBEROS, new WsmanKerberosSchemeFactory(!kerberosAddPortToSpn, spnServiceClass, unmappedAddress, unmappedPort, this));
//                httpClient.getAuthSchemes().register(SPNEGO, new WsmanSPNegoSchemeFactory(!kerberosAddPortToSpn, spnServiceClass, unmappedAddress, unmappedPort, this));
                configureAuthentication(KERBEROS, new KerberosPrincipal(username));
//                configureAuthentication(SPNEGO, new KerberosPrincipal(username));
            }

            httpClient.getParams().setBooleanParameter(HANDLE_AUTHENTICATION, true);

            httpContext = new BasicHttpContext();

            if(enableKerberos) {
                // start encryption
                sendRequest("", null);
            }
        } catch (Exception exc) {
            throw new WinRmRuntimeIOException("Error connecting to " + targetURL, exc);
        }
        logger.trace("Connected to [{}]", targetURL);
    }

    void setGSSContext(GSSContext ctx) {
        this.kerberosGSSContext = ctx;
    }

    private void configureTrust() throws NoSuchAlgorithmException,
        KeyManagementException, KeyStoreException, UnrecoverableKeyException {

        if (!"https".equalsIgnoreCase(targetURL.getProtocol())) {
            return;
        }

        final TrustStrategy trustStrategy = httpsCertTrustStrategy.getStrategy();
        final X509HostnameVerifier hostnameVerifier = httpsHostnameVerifyStrategy.getVerifier();
        final SSLSocketFactory socketFactory = new SSLSocketFactory(trustStrategy, hostnameVerifier);
        final Scheme sch = new Scheme("https", 443, socketFactory);
        httpClient.getConnectionManager().getSchemeRegistry().register(sch);
    }

    private void configureAuthentication(final String scheme, final Principal principal) {
        httpClient.getCredentialsProvider().setCredentials(new AuthScope(ANY_HOST, ANY_PORT, ANY_REALM, scheme), new Credentials() {
            public Principal getUserPrincipal() {
                return principal;
            }

            public String getPassword() {
                return password;
            }
        });
    }

    public void disconnect() {
        logger.trace("Disconnecting from [{}]", targetURL);

        if (enableKerberos) {
            checkNotNull(kerberosLoginContext, "kerberosLoginContext == null: Invoke connect() before calling disconnect()");

            try {
                logger.trace("Logging out of Kerberos");
                kerberosLoginContext.logout();
            } catch (LoginException exc) {
                logger.warn("Error logging out of Kerberos login context", exc);
            }
        }

        logger.trace("Shutting down ");
        httpClient.getConnectionManager().shutdown();

        logger.trace("Disconnected from [{}]", targetURL);
    }

    Document sendRequest(final Document requestDocument, final SoapAction soapAction) {
        final String requestBody = WinRmClient.toString(requestDocument);
        final String responseBody = sendRequest(requestBody, soapAction);
        
        Document responseDocument;
        try {
            responseDocument = DocumentHelper.parseText(responseBody);
        } catch (DocumentException exc) {
            throw new WinRmRuntimeIOException("Cannot parse", requestDocument, null, exc);
        }

        logDocument("Response body:", responseDocument);

        return responseDocument;
    }
    

    synchronized String sendRequest(final String requestDocument, final SoapAction soapAction) {
        if (enableKerberos) {
            return runPrivileged(new PrivilegedSendMessage(requestDocument, soapAction));
        } else {
            return doSendRequest(requestDocument, soapAction);
        }
    }

    /**
     * Performs the JAAS login and run the sendRequest method within a privileged scope.
     */
    private String runPrivileged(final PrivilegedSendMessage privilegedSendMessage) {
        try {
            checkNotNull(kerberosLoginContext, "kerberosLoginContext == null: Invoke connect() before using the WinRMClient");

            return Subject.doAs(kerberosLoginContext.getSubject(), privilegedSendMessage);
        } catch (PrivilegedActionException exc) {
            throw new WinRmRuntimeIOException("Failure sending message on " + targetURL + " error: " + exc.getMessage(),
                null, null, exc.getException());
//            throw new WinRmRuntimeIOException("Failure sending message on " + targetURL + " error: " + exc.getMessage(),
//                privilegedSendMessage.getRequestBody(), null, exc.getException());
        }
    }

    /**
     * PrivilegedExceptionAction that wraps the internal sendRequest
     */
    private class PrivilegedSendMessage implements PrivilegedExceptionAction<String> {
        private String requestBody;
        private SoapAction soapAction;

        private PrivilegedSendMessage(final String requestDocument, final SoapAction soapAction) {
            this.requestBody = requestDocument;
            this.soapAction = soapAction;
        }

        @Override
        public String run() throws Exception {
            return WinRmHttpClient.this.doSendRequest(requestBody, soapAction);
        }

        public String getRequestBody() {
            return requestBody;
        }
    }

    /**
     * Internal sendRequest, performs the HTTP request and returns the result document.
     */
    private String doSendRequest(final String requestBody, final SoapAction soapAction) {
        try {
            final HttpPost request = new HttpPost(targetURL.toURI());
try {
//            if (soapAction != null) {
//                request.setHeader("SOAPAction", soapAction.getValue());
//            }

            
            final HttpEntity entity;

            if(!enableKerberos || kerberosGSSContext == null) {
                entity = createEntity(requestBody);
                logger.trace("Request:\nPOST {}\n{}", targetURL, requestBody);
            } else {
                byte[] requestBytes = requestBody.getBytes("UTF-8");
                MessageProp mp = new MessageProp(false);
                byte[] wrapped = kerberosGSSContext.wrap(requestBytes, 0, requestBytes.length, mp);
                // int padlength = 8 - (requestBytes.length % 8);
                int padlength = 0;
                System.out.println("orig.length=" + requestBytes.length + ", wrapped.length=" + wrapped.length + ", padlength=" + padlength);
                byte[] beforeBytes = ("--Encrypted Boundary\r" + 
"Content-Type: application/HTTP-Kerberos-session-encrypted\r" +
"OriginalContent: type=application/soap+xml;charset=UTF-8;Length=" + (requestBytes.length + padlength) + "\r" +
"--Encrypted Boundary\r" +
"Content-Type: application/octet-stream\r").getBytes(Charset.forName("UTF-8"));
                byte[] headerBytes = new byte [4];
                headerBytes[3] = 0;
                headerBytes[1] = 0;
                headerBytes[2] = 0;
                headerBytes[0] = (byte) (wrapped.length - requestBytes.length - padlength);
                byte[] afterBytes = ("--Encrypted Boundary\r").getBytes(Charset.forName("UTF-8"));
                byte[] completeBytes = Bytes.concat(beforeBytes, headerBytes, wrapped, afterBytes);
                entity = new ByteArrayEntity(completeBytes, null);
                String header = "multipart/encrypted;protocol=\"application/HTTP-Kerberos-session-encrypted\";boundary=\"Encrypted Boundary\"";
                ((ByteArrayEntity) entity).setContentType(header);
                logger.trace("Request is encrypted");
                logger.trace("Request:\nPOST {}\n{}", targetURL, new String(completeBytes, "UTF-8"));
            }

            request.setEntity(entity);

            final HttpResponse response = httpClient.execute(request, httpContext);

            logResponseHeaders(response);

            if (response.getStatusLine().getStatusCode() != 200) {
                throw new WinRmRuntimeIOException(String.format("Unexpected HTTP response on %s: %s %s",
                    targetURL, response.getStatusLine().getStatusCode(), response.getStatusLine().getReasonPhrase()));
            }

            Header[] authenticateHeaders = response.getHeaders("WWW-Authenticate");
            for (Header ah : authenticateHeaders) {
                if (ah.getValue().startsWith("Negotiate ") || ah.getValue().startsWith("Kerberos ")) {
                    Base64 base64codec = new Base64();
                    int len = "Kerberos ".length();
                    if(ah.getValue().startsWith("Negotiate ")) {
                        len = "Negotiate ".length();
                    }
                    String tokenStr = ah.getValue().substring(len);
                    byte[] token = base64codec.decode(tokenStr);
                    System.err.println("established (3a): " + kerberosGSSContext.isEstablished());
                    System.err.println("protReady (3a): " + kerberosGSSContext.isProtReady());
                    kerberosGSSContext.initSecContext(token, 0, token.length);
                    System.err.println("established (b): " + kerberosGSSContext.isEstablished());
                    System.err.println("protReady (3b): " + kerberosGSSContext.isProtReady());
                }
            }

            final String responseBody = handleResponse(response, httpContext);
            return responseBody;
} finally {
    request.releaseConnection();
}
        } catch (WinRmRuntimeIOException exc) {
            throw exc;
        } catch (Exception exc) {
            throw new WinRmRuntimeIOException("Error when sending request to " + targetURL, null, null, exc);
//            throw new WinRmRuntimeIOException("Error when sending request to " + targetURL, requestBody, null, exc);
        }
    }

    protected String handleResponse(final HttpResponse response, final HttpContext context) throws IOException {
        final HttpEntity entity = response.getEntity();
        if (null == entity.getContentType()) {
            EntityUtils.consume(response.getEntity());
            return "";
        }

        if(!entity.getContentType().getValue().startsWith("application/soap+xml")) {
            throw new WinRmRuntimeIOException("Error when sending request to " + targetURL + "; Unexpected content-type: " + entity.getContentType());
        }

        final InputStream is = entity.getContent();
        final Writer writer = new StringWriter();
        final Reader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        try {
            int n;
            final char[] buffer = new char[1024];
            while ((n = reader.read(buffer)) != -1) {
                writer.write(buffer, 0, n);
            }
        } finally {
            Closeables.closeQuietly(reader);
            Closeables.closeQuietly(is);
            EntityUtils.consume(response.getEntity());
        }

        return writer.toString();
    }

    private static void logDocument(String caption, final Document document) {
        if (!logger.isTraceEnabled()) {
            return;
        }

        StringWriter text = new StringWriter();
        try {
            XMLWriter writer = new XMLWriter(text, OutputFormat.createPrettyPrint());
            writer.write(document);
            writer.close();
        } catch (IOException e) {
            logger.trace("{}\n{}", caption, e);
        }

        logger.trace("{}\n{}", caption, text);
    }

    private static void logResponseHeaders(final HttpResponse response) {
        if (!logger.isTraceEnabled()) {
            return;
        }

        StringBuilder headers = new StringBuilder();
        for (final Header header : response.getAllHeaders()) {
            headers.append(header.getName()).append(": ").append(header.getValue()).append("\n");
        }

        logger.trace("Response headers:\n{}", headers);
    }

    /**
     * Create the HttpEntity to send in the request.
     */
    protected HttpEntity createEntity(final String requestDocAsString) {
        return new StringEntity(requestDocAsString, ContentType.create("application/soap+xml", "UTF-8"));
    }

    URL getTargetURL() {
        return targetURL;
    }

    void setHttpsCertTrustStrategy(WinrmHttpsCertificateTrustStrategy httpsCertTrustStrategy) {
        this.httpsCertTrustStrategy = httpsCertTrustStrategy;
    }

    void setHttpsHostnameVerifyStrategy(WinrmHttpsHostnameVerificationStrategy httpsHostnameVerifyStrategy) {
        this.httpsHostnameVerifyStrategy = httpsHostnameVerifyStrategy;
    }

    void setKerberosUseHttpSpn(boolean kerberosUseHttpSpn) {
        this.kerberosUseHttpSpn = kerberosUseHttpSpn;
    }

    void setKerberosAddPortToSpn(boolean kerberosAddPortToSpn) {
        this.kerberosAddPortToSpn = kerberosAddPortToSpn;
    }

    void setKerberosDebug(boolean kerberosDebug) {
        this.kerberosDebug = kerberosDebug;
    }

    private static Logger logger = LoggerFactory.getLogger(WinRmHttpClient.class);

}