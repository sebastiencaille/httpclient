/*
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */
package org.apache.hc.client5.http.impl.auth;

import java.net.Authenticator;
import java.net.MalformedURLException;
import java.net.PasswordAuthentication;
import java.net.URL;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.hc.client5.http.auth.AuthScope;
import org.apache.hc.client5.http.auth.Credentials;
import org.apache.hc.client5.http.auth.CredentialsStore;
import org.apache.hc.client5.http.auth.NTCredentials;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.config.AuthSchemes;
import org.apache.hc.client5.http.impl.sync.BasicCredentialsProvider;
import org.apache.hc.core5.annotation.ThreadSafe;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.http.protocol.HttpCoreContext;
import org.apache.hc.core5.util.Args;

/**
 * Implementation of {@link CredentialsStore} backed by standard
 * JRE {@link Authenticator}.
 *
 * @since 4.3
 */
@ThreadSafe
public class SystemDefaultCredentialsProvider implements CredentialsStore {

    private static final Map<String, String> SCHEME_MAP;

    static {
        SCHEME_MAP = new ConcurrentHashMap<>();
        SCHEME_MAP.put(AuthSchemes.BASIC.toUpperCase(Locale.ROOT), "Basic");
        SCHEME_MAP.put(AuthSchemes.DIGEST.toUpperCase(Locale.ROOT), "Digest");
        SCHEME_MAP.put(AuthSchemes.NTLM.toUpperCase(Locale.ROOT), "NTLM");
        SCHEME_MAP.put(AuthSchemes.SPNEGO.toUpperCase(Locale.ROOT), "SPNEGO");
        SCHEME_MAP.put(AuthSchemes.KERBEROS.toUpperCase(Locale.ROOT), "Kerberos");
    }

    private static String translateScheme(final String key) {
        if (key == null) {
            return null;
        }
        final String s = SCHEME_MAP.get(key);
        return s != null ? s : key;
    }

    private final BasicCredentialsProvider internal;

    /**
     * Default constructor.
     */
    public SystemDefaultCredentialsProvider() {
        super();
        this.internal = new BasicCredentialsProvider();
    }

    @Override
    public void setCredentials(final AuthScope authscope, final Credentials credentials) {
        internal.setCredentials(authscope, credentials);
    }

    private static PasswordAuthentication getSystemCreds(
            final AuthScope authscope,
            final Authenticator.RequestorType requestorType,
            final HttpContext context) {
        final String hostname = authscope.getHost();
        final int port = authscope.getPort();
        final HttpHost origin = authscope.getOrigin();
        final String protocol = origin != null ? origin.getSchemeName() :
                (port == 443 ? "https" : "http");

        final URL targetHostURL = getTargetHostURL(context);
        // use null addr, because the authentication fails if it does not exactly match the expected realm's host
        return Authenticator.requestPasswordAuthentication(
                hostname,
                null,
                port,
                protocol,
                authscope.getRealm(),
                translateScheme(authscope.getScheme()),
                targetHostURL,
                requestorType);
    }

    private static URL getTargetHostURL(final HttpContext context) {
        if (context == null) {
            // Fluent case.
            return null;
        }

        final HttpRequest httpRequest = (HttpRequest)context.getAttribute(HttpCoreContext.HTTP_REQUEST);
        final String uri = httpRequest.getRequestLine().getUri();
        try {
            return new URL(uri);
        } catch (final MalformedURLException e) {
            throw new IllegalStateException("Unexpected request url format: " + uri, e);
        }
    }

    @Override
    public Credentials getCredentials(final AuthScope authscope, final HttpContext context) {
        Args.notNull(authscope, "Auth scope");
        final Credentials localcreds = internal.getCredentials(authscope, context);
        if (localcreds != null) {
            return localcreds;
        }
        final String host = authscope.getHost();
        if (host != null) {
            PasswordAuthentication systemcreds =
                            getSystemCreds(authscope, Authenticator.RequestorType.SERVER, context);
            if (systemcreds == null) {
                systemcreds = getSystemCreds(authscope, Authenticator.RequestorType.PROXY, context);
            }
            if (systemcreds == null) {
                final String proxyHost = System.getProperty("http.proxyHost");
                if (proxyHost != null) {
                    final String proxyPort = System.getProperty("http.proxyPort");
                    if (proxyPort != null) {
                        try {
                            final AuthScope systemScope = new AuthScope(proxyHost, Integer.parseInt(proxyPort));
                            if (authscope.match(systemScope) >= 0) {
                                final String proxyUser = System.getProperty("http.proxyUser");
                                if (proxyUser != null) {
                                    final String proxyPassword = System.getProperty("http.proxyPassword");
                                    systemcreds = new PasswordAuthentication(proxyUser, proxyPassword != null ? proxyPassword.toCharArray() : new char[] {});
                                }
                            }
                        } catch (NumberFormatException ex) {
                        }
                    }
                }
            }
            if (systemcreds != null) {
                final String domain = System.getProperty("http.auth.ntlm.domain");
                if (domain != null) {
                    return new NTCredentials(systemcreds.getUserName(), systemcreds.getPassword(), null, domain);
                }
                if (AuthSchemes.NTLM.equalsIgnoreCase(authscope.getScheme())) {
                    // Domian may be specified in a fully qualified user name
                    return new NTCredentials(
                            systemcreds.getUserName(), systemcreds.getPassword(), null, null);
                }
                return new UsernamePasswordCredentials(systemcreds.getUserName(), systemcreds.getPassword());
            }
        }
        return null;
    }

    @Override
    public void clear() {
        internal.clear();
    }

}
