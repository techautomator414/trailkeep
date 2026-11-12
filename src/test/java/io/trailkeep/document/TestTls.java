// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.boot.ssl.SslStoreBundle;

/**
 * A throwaway certificate authority and one server certificate it signed, generated at test run time with the JDK's
 * own {@code keytool} into a temporary directory: nothing is committed, and the store password is random. The
 * client side trusts only that CA, through a Spring Boot SSL bundle, exactly as the application does. There is no
 * trust-all manager and no disabled hostname check.
 */
final class TestTls {

    private static final String BUNDLE = "clamd";

    private static @Nullable TestTls shared;

    private final Path dir;
    private final char[] password;
    private final KeyStore serverKeys;
    private final KeyStore trust;

    private TestTls(Path dir, char[] password, KeyStore serverKeys, KeyStore trust) {
        this.dir = dir;
        this.password = password.clone();
        this.serverKeys = serverKeys;
        this.trust = trust;
    }

    /**
     * The CA and {@code localhost} certificate most tests share, created once per test JVM.
     *
     * @return the shared material
     */
    static synchronized TestTls shared() {
        TestTls tls = shared;
        if (tls == null) {
            tls = issue("dns:localhost,ip:127.0.0.1");
            shared = tls;
        }
        return tls;
    }

    /**
     * Creates a new CA and a server certificate for the given subject alternative names.
     *
     * @param san keytool's {@code san=} value, for example {@code dns:localhost}
     * @return the material
     */
    static TestTls issue(String san) {
        try {
            Path dir = Files.createTempDirectory("test-tls-");
            dir.toFile().deleteOnExit();
            String pass = UUID.randomUUID().toString();
            generate(dir, pass, san);
            KeyStore serverKeys = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(dir.resolve("server.p12"))) {
                serverKeys.load(in, pass.toCharArray());
            }
            KeyStore trust = KeyStore.getInstance("PKCS12");
            trust.load(null, null);
            try (InputStream in = Files.newInputStream(dir.resolve("ca.crt"))) {
                trust.setCertificateEntry(
                        "ca", CertificateFactory.getInstance("X.509").generateCertificate(in));
            }
            return new TestTls(dir, pass.toCharArray(), serverKeys, trust);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A CA (basic constraints CA), then a server key pair whose certificate the CA signs, chained in server.p12. */
    private static void generate(Path dir, String pass, String san) throws IOException {
        String ec = " -keyalg EC -groupname secp256r1 -validity 2";
        keytool(dir, pass, "-genkeypair -alias ca -dname CN=throwaway-test-ca -ext bc:c -keystore ca.p12" + ec);
        keytool(dir, pass, "-genkeypair -alias server -dname CN=clamd-test-server -keystore server.p12" + ec);
        keytool(dir, pass, "-certreq -alias server -keystore server.p12 -file server.csr");
        keytool(
                dir,
                pass,
                "-gencert -alias ca -keystore ca.p12 -infile server.csr -outfile server.crt -rfc"
                        + " -validity 2 -ext san=" + san + " -ext eku=serverAuth");
        keytool(dir, pass, "-exportcert -alias ca -keystore ca.p12 -rfc -file ca.crt");
        keytool(dir, pass, "-importcert -alias ca -keystore server.p12 -file ca.crt -noprompt");
        keytool(dir, pass, "-importcert -alias server -keystore server.p12 -file server.crt");
    }

    /** Runs keytool with space-separated arguments (none contains a space) on a PKCS12 store. */
    private static void keytool(Path dir, String pass, String args) throws IOException {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        command.addAll(List.of(args.split(" ")));
        command.addAll(List.of("-storetype", "PKCS12", "-storepass", pass));
        Process process = new ProcessBuilder(command)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        try {
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
                throw new IllegalStateException("keytool " + args + " failed: " + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /**
     * Spring Boot SSL bundles holding one bundle, {@code clamd}, that trusts only this CA.
     *
     * @return the bundles
     */
    SslBundles bundles() {
        return new DefaultSslBundleRegistry(BUNDLE, SslBundle.of(SslStoreBundle.of(null, null, trust)));
    }

    /**
     * The {@code clamd} bundle for an application context started outside this package, as arguments: a PKCS12 trust
     * store holding only this CA, written to this material's temporary directory under its random password.
     *
     * @return the arguments that point the bundle at it
     */
    List<String> trustStoreArguments() {
        Path store = dir.resolve("trust.p12");
        try (OutputStream out = Files.newOutputStream(store)) {
            trust.store(out, password);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        String prefix = "--spring.ssl.bundle.jks." + BUNDLE + ".truststore.";
        return List.of(
                prefix + "location=file:" + store,
                prefix + "type=PKCS12",
                prefix + "password=" + String.valueOf(password));
    }

    /**
     * A TLS server socket on a free loopback port, presenting the server certificate.
     *
     * @return the bound socket
     */
    SSLServerSocket serverSocket() {
        try {
            KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            keys.init(serverKeys, password);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys.getKeyManagers(), null, null);
            return (SSLServerSocket)
                    context.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getLoopbackAddress());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Writes the server's certificate chain and private key as PEM files for a TLS sidecar to read, in this
     * material's temporary directory.
     *
     * @return the chain file; the key is {@code server-key.pem} beside it
     */
    Path writePem() {
        try {
            StringBuilder chain = new StringBuilder();
            for (Certificate certificate : serverKeys.getCertificateChain("server")) {
                chain.append(pem("CERTIFICATE", certificate.getEncoded()));
            }
            Path chainFile = Files.writeString(dir.resolve("server-chain.pem"), chain);
            byte[] key = serverKeys.getKey("server", password).getEncoded();
            Files.writeString(dir.resolve("server-key.pem"), pem("PRIVATE KEY", key));
            return chainFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String pem(String label, byte[] der) {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(der);
        return "-----BEGIN " + label + "-----\n" + body + "\n-----END " + label + "-----\n";
    }
}
