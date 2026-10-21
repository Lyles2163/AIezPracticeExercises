package org.leon.aimodule.rag.config;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest5_client.Rest5ClientTransport;
import co.elastic.clients.transport.rest5_client.low_level.Rest5Client;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Base64;

public final class EsClientFactory {

    private EsClientFactory() {
    }

    public static ElasticsearchClient create(RagProperties.Elasticsearch props) throws Exception {
        Rest5Client rest5 = Rest5Client.builder(URI.create(props.getUris()))
                .setSSLContext(sslContext(props.getCaCertPath()))
                .setDefaultHeaders(new Header[]{basicAuth(props.getUsername(), props.getPassword())})
                .build();
        ElasticsearchTransport transport = new Rest5ClientTransport(rest5, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }

    public static SSLContext sslContext(String caCertPath) throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Certificate ca;
        try (InputStream in = Files.newInputStream(Path.of(caCertPath))) {
            ca = cf.generateCertificate(in);
        }
        KeyStore trustStore = KeyStore.getInstance("PKCS12");
        trustStore.load(null, null);
        trustStore.setCertificateEntry("es-ca", ca);
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trustStore);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, tmf.getTrustManagers(), null);
        return context;
    }

    static Header basicAuth(String username, String password) {
        String raw = username + ":" + password;
        return new BasicHeader("Authorization",
                "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8)));
    }
}
