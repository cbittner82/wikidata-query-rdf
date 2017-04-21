package org.wikidata.query.rdf.tool;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.wikidata.query.rdf.tool.OptionsUtils.BasicOptions;
import org.wikidata.query.rdf.tool.OptionsUtils.WikibaseOptions;
import org.wikidata.query.rdf.tool.wikibase.WikibaseRepository;

import com.lexicalscope.jewel.cli.Option;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response.IStatus;
import fi.iki.elonen.NanoHTTPD.Response.Status;

/**
 * Simply http proxy that returns preprogrammed error messages every few
 * requests.
 */
public class Proxy extends NanoHTTPD {
    private static final Logger log = LoggerFactory.getLogger(Proxy.class);

    /**
     * Options for this proxy for parsing with JewelCLI.
     */
    @SuppressWarnings("checkstyle:javadocmethod")
    public interface ProxyOptions extends BasicOptions, WikibaseOptions {
        @Option(shortName = "p", description = "Port")
        int port();

        @Option(shortName = "e", description = "Error")
        int error();

        @Option(shortName = "m", description = "Error thrown every m requests")
        int errorMod();

        @Option(defaultValue = "https", description = "Wikidata url scheme")
        String wikibaseScheme();

        @Option(description = "Immediately return and leave the server running in a thread")
        boolean embedded();
    }

    /**
     * Run from the CLI.
     *
     * @throws IOException if one is thrown trying to get the port
     */
    public static void main(String[] args) throws IOException {
        ProxyOptions options = OptionsUtils.handleOptions(ProxyOptions.class, args);
        WikibaseRepository.Uris wikibase = new WikibaseRepository.Uris(options.wikibaseScheme(), options.wikibaseHost());
        Proxy p = new Proxy(options.port(), wikibase, buildErrorStatus(options.error()), options.errorMod());
        p.start();
        if (options.embedded()) {
            return;
        }
        while (true) {
            try {
                Thread.sleep(TimeUnit.MINUTES.toSeconds(1));
            } catch (InterruptedException e) {
                break;
            }
        }
        p.stop();
    }

    /**
     * Build a NanoHTTPD compatible IStatus for an error code.
     */
    private static IStatus buildErrorStatus(int errorCode) {
        /*
         * Some codes aren't supported by NanoHTTPD "natively" so we add them
         * ourselves.
         */
        switch (errorCode) {
        case 503:
            return new SimpleStatus(503, "Internal server error");
        default:
        }
        // If it is supported by NanoHTTPD use its status
        for (Status status : Status.values()) {
            if (status.getRequestStatus() == errorCode) {
                return status;
            }
        }
        // Otherwise throw the user an error
        CliUtils.ForbiddenOk.systemDotErr().printf(Locale.ROOT, "Unknown error code:  %s\n", errorCode);
        System.exit(1);
        return null;
    }

    /**
     * How many requests we've served?
     */
    private final AtomicLong requestCount = new AtomicLong(0);
    /**
     * Actually makes the http calls. We can afford to use the default client in
     * this simple test tool.
     */
    private final CloseableHttpClient client = HttpClients.createDefault();
    /**
     * The root contact point for wikibase.
     */
    private final WikibaseRepository.Uris wikibase;
    /**
     * The status to return for the error.
     */
    private final IStatus errorStatus;
    /**
     * Throw an error every this many requests.
     */
    private final int errorMod;

    public Proxy(int port, WikibaseRepository.Uris wikibase, IStatus errorStatus, int errorMod) {
        super(port);
        this.wikibase = wikibase;
        this.errorStatus = errorStatus;
        this.errorMod = errorMod;
    }

    @Override
    public void start() throws IOException {
        super.start();
        log.info("Started proxy to {} on {}", wikibase.getHost(), getListeningPort());
    }

    @Override
    public Response serve(IHTTPSession session) {
        log.debug("Serving {} {}", session.getMethod(), session.getUri());
        long currentRequest = requestCount.incrementAndGet();
        if (currentRequest % errorMod == 1) {
            log.debug("Returning an {}:{}", errorStatus.getRequestStatus(), errorStatus.getDescription());
            return new Response(errorStatus, NanoHTTPD.MIME_PLAINTEXT, "dummy error");
        }
        try {
            URI uri = buildUri(session.getUri(), session.getParms());
            log.debug("Proxying to {}", uri);
            HttpRequestBase request = buildRequest(session.getMethod(), uri);
            // TODO we totally ignore headers
            HttpClientUtils.ignoreCookies(request);
            CloseableHttpResponse response = client.execute(request);
            /*
             * Note that I'm intentionally not closing the response because the
             * caller will close the wrapped input stream which is probably good
             * enough.
             */
            IStatus status = new SimpleStatus(response.getStatusLine());
            String mimeType = response.getEntity().getContentType().getValue();
            Response result = new Response(status, mimeType, response.getEntity().getContent());
            result.setChunkedTransfer(true);
            return result;
        } catch (URISyntaxException e) {
            return new Response(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Invalid URI:  "
                    + e.getMessage());
        } catch (IOException e) {
            return new Response(NanoHTTPD.Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT,
                    "Errroc communicating with other side of proxy:  " + e.getMessage());
        }
    }

    /**
     * Build the URI for the proxied request.
     *
     * @throws URISyntaxException when the builder doesn't like the URI syntax.
     *             It is unlikely to happen but we catch it so we can send an
     *             error back to the client anyway.
     */
    private URI buildUri(String path, Map<String, String> params) throws URISyntaxException {
        URIBuilder builder = wikibase.builder().setPath(path);
        for (Map.Entry<String, String> param : params.entrySet()) {
            builder.addParameter(param.getKey(), param.getValue());
        }
        return builder.build();
    }

    /**
     * Build the proxied request.
     */
    private HttpRequestBase buildRequest(Method method, URI uri) {
        // Add extra case arms to support more types of requests
        switch (method) {
        case GET:
            return new HttpGet(uri);
        default:
            throw new UnsupportedOperationException("Unsupported method:  " + method);
        }
    }

    /**
     * Wraps response from the upstream connection.
     */
    private static class SimpleStatus implements IStatus {
        /**
         * HTTP error code.
         */
        private final int code;
        /**
         * Description of the error.
         */
        private final String description;

        SimpleStatus(StatusLine status) {
            this(status.getStatusCode(), status.getReasonPhrase());
        }

        SimpleStatus(int code, String description) {
            this.code = code;
            this.description = description;
        }

        @Override
        public int getRequestStatus() {
            return code;
        }

        @Override
        public String getDescription() {
            // Silly API
            return code + " " + description;
        }
    }
}
