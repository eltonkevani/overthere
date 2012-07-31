package com.xebialabs.overthere.cifs;

import static com.xebialabs.overthere.cifs.CifsConnectionBuilder.CIFS_PROTOCOL;
import static com.xebialabs.overthere.cifs.WinrmHttpsCertificateTrustStrategy.STRICT;
import static com.xebialabs.overthere.cifs.WinrmHttpsHostnameVerificationStrategy.BROWSER_COMPATIBLE;

import java.util.Map;

import com.google.common.collect.ImmutableMap;
import com.xebialabs.overthere.ConnectionOptions;
import com.xebialabs.overthere.OverthereConnection;
import com.xebialabs.overthere.cifs.telnet.CifsTelnetConnection;
import com.xebialabs.overthere.cifs.winrm.CifsWinRmConnection;
import com.xebialabs.overthere.spi.AddressPortMapper;
import com.xebialabs.overthere.spi.OverthereConnectionBuilder;
import com.xebialabs.overthere.spi.Protocol;

/**
 * Builds CIFS connections.
 */
@Protocol(name = CIFS_PROTOCOL)
public class CifsConnectionBuilder implements OverthereConnectionBuilder {

    /**
     * Name of the protocol handled by this connection builder, i.e. "cifs".
     */
    public static final String CIFS_PROTOCOL = "cifs";

    /**
     * Name of the {@link ConnectionOptions connection option} used to specify the {@link CifsConnectionType CIFS
     * connection type} to use.
     */
    public static final String CONNECTION_TYPE = "connectionType";

    /**
     * Default port (23) used when the {@link #CONNECTION_TYPE CIFS connection type} is {#link
     * {@link CifsConnectionType#TELNET TELNET}.
     */
    public static final int DEFAULT_TELNET_PORT = 23;

    /**
     * Default port (5985) used when the {@link #CONNECTION_TYPE CIFS connection type} is {#link
     * {@link CifsConnectionType#WINRM_HTTP WINRM_HTTP}.
     */
    public static final int DEFAULT_WINRM_HTTP_PORT = 5985;

    /**
     * Default port (5986) used when the {@link #CONNECTION_TYPE CIFS connection type} is {#link
     * {@link CifsConnectionType#WINRM_HTTPS WINRM_HTTPS}.
     */
    public static final int DEFAULT_WINRM_HTTPS_PORT = 5986;

    /**
     * Name of the {@link ConnectionOptions connection option} used to specify the CIFS port to connect to.
     */
    public static final String CIFS_PORT = "cifsPort";

    /**
     * Default value (445) for the {@link ConnectionOptions connection option} used to specify the CIFS port to connect
     * to.
     */
    public static final int DEFAULT_CIFS_PORT = 445;

    /**
     * Name of the {@link ConnectionOptions connection option} used to specify the path to share mappings to use for
     * CIFS, specified as a <tt>Map&lt;String, String&gt;</tt>, e.g. "C:\IBM\WebSphere" -> "WebSphere". If a path is not
     * explicitly mapped to a share the administrative share will be used..
     */
    public static final String PATH_SHARE_MAPPINGS = "pathShareMappings";

    /**
     * Default value (empty map) for the {@link ConnectionOptions connection option} used to specify the path to share
     * mappings to use for CIFS.
     */
    public static final Map<String, String> PATH_SHARE_MAPPINGS_DEFAULT = ImmutableMap.of();

    /**
     * Name of the {@link ConnectionOptions connection option} used to specify the context (URI) used by WinRM.
     */
    public static final String CONTEXT = "winrmContext";

    /**
     * Default value (/wsman) of the {@link ConnectionOptions connection option} used to specify the context (URI) used
     * by WinRM.
     */
    public static final String DEFAULT_WINRM_CONTEXT = "/wsman";

    /**
     * Name of the {@link ConnectionOptions connection option} used to specify the WinRM timeout in <a
     * href="http://www.w3.org/TR/xmlschema-2/#isoformats">XML schema duration format</a>
     */
    public static final String TIMEMOUT = "winrmTimeout";

    /**
     * Default value (PT60.000S) of the {@link ConnectionOptions connection option} used to specify the WinRM timeout.
     */
    public static final String DEFAULT_TIMEOUT = "PT60.000S";

    /**
     * Name of the {@link ConnectionOptions connection option} used to specify the WinRM envelop size in bytes to use.
     */
    public static final String ENVELOP_SIZE = "winrmEnvelopSize";

    /**
     * Default value (153600) of the {@link ConnectionOptions connection option} used to specify the WinRM envelop size
     * in bytes to use.
     */
    public static final int DEFAULT_ENVELOP_SIZE = 153600;

    /**
     * Name of the {@link ConnectionOptions connection option} used to specify the WinRM locale to use.
     */
    public static final String LOCALE = "winrmLocale";

    /**
     * Default value (en-US) of the {@link ConnectionOptions connection option} used to specify the WinRM locale to use.
     */
    public static final String DEFAULT_LOCALE = "en-US";

    /**
     * Name of the {@link ConnectionOptions connection option} used to specify whether to enable debug output for
     * Kerberos JAAS authentication.
     */
    public static final String DEBUG_KERBEROS_AUTH = "winrmDebugKerberosAuth";

    /**
     * Default value (false) of the {@link ConnectionOptions connection option} used to specify whether to enable debug
     * output for Kerberos JAAS authentication.
     */
    public static final boolean DEFAULT_DEBUG_KERBEROS_AUTH = false;

    /**
     * Name of the {@link ConnectionOptions connection option} used to specify the
     * {@link WinrmHttpsCertificateTrustStrategy} for WinRM HTTPS connections.
     */
    public static final String WINRM_HTTPS_CERTIFICATE_TRUST_STRATEGY = "winrmHttpsCertificateTrustStrategy";

    /**
     * Default value ({@link WinrmHttpsCertificateTrustStrategy#STRICT}) of the {@link ConnectionOptions connection
     * option} used to specify the {@link WinrmHttpsCertificateTrustStrategy} for WinRM HTTPS connections.
     */
    public static final WinrmHttpsCertificateTrustStrategy DEFAULT_WINRM_HTTPS_CERTIFICATE_TRUST_STRATEGY = STRICT;

    /**
     * Name of the {@link ConnectionOptions connection option} used to specify the
     * {@link WinrmHttpsHostnameVerificationStrategy} for WinRM HTTPS connections.
     */
    public static final String WINRM_HTTPS_HOSTNAME_VERIFICATION_STRATEGY = "winrmHttpsHostnameVerificationStrategy";

    /**
     * Default value ({@link WinrmHttpsHostnameVerificationStrategy#BROWSER_COMPATIBLE}) of the
     * {@link ConnectionOptions connection option} used to specify the {@link WinrmHttpsHostnameVerificationStrategy}
     * for WinRM HTTPS connections.
     */
    public static final WinrmHttpsHostnameVerificationStrategy DEFAULT_WINRM_HTTPS_HOSTNAME_VERIFICATION_STRATEGY = BROWSER_COMPATIBLE;

    private OverthereConnection connection;

    public CifsConnectionBuilder(String type, ConnectionOptions options, AddressPortMapper mapper) {
        CifsConnectionType cifsConnectionType = options.getEnum(CONNECTION_TYPE, CifsConnectionType.class);

        switch (cifsConnectionType) {
        case TELNET:
            connection = new CifsTelnetConnection(type, options, mapper);
            break;
        case WINRM_HTTP:
        case WINRM_HTTPS:
            connection = new CifsWinRmConnection(type, options, mapper);
            break;
        default:
            throw new IllegalArgumentException("Unknown CIFS connection type " + cifsConnectionType);
        }
    }

    @Override
    public OverthereConnection connect() {
        return connection;
    }

    @Override
    public String toString() {
        return connection.toString();
    }

}
