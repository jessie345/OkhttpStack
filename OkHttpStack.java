/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.yangcong345.android.phone.manager;

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.Request.Method;
import com.android.volley.toolbox.HttpStack;

import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.ProtocolVersion;
import org.apache.http.StatusLine;
import org.apache.http.entity.BasicHttpEntity;
import org.apache.http.message.BasicHeader;
import org.apache.http.message.BasicHttpResponse;
import org.apache.http.message.BasicStatusLine;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;

import okhttp3.CacheControl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * An {@link HttpStack} based on okHttp.
 */
public class OkHttpStack implements HttpStack {

    /**
     * An interface for transforming URLs before use.
     */
    public interface UrlRewriter {
        /**
         * Returns a URL to use instead of the provided one, or null to indicate
         * this URL should not be used at all.
         */
        public String rewriteUrl(String originalUrl);
    }

    private final UrlRewriter mUrlRewriter;
    private final SSLSocketFactory mSslSocketFactory;

    public OkHttpStack() {
        this(null);
    }

    /**
     * @param urlRewriter Rewriter to use for request URLs
     */
    public OkHttpStack(UrlRewriter urlRewriter) {
        this(urlRewriter, null);
    }

    /**
     * @param urlRewriter      Rewriter to use for request URLs
     * @param sslSocketFactory SSL factory to use for HTTPS connections
     */
    public OkHttpStack(UrlRewriter urlRewriter, SSLSocketFactory sslSocketFactory) {
        mUrlRewriter = urlRewriter;
        mSslSocketFactory = sslSocketFactory;
    }

    @Override
    public synchronized HttpResponse performRequest(Request<?> request, Map<String, String> additionalHeaders)
            throws IOException, AuthFailureError {
        String url = request.getUrl();

        if (mUrlRewriter != null) {
            String rewritten = mUrlRewriter.rewriteUrl(url);
            if (rewritten == null) {
                throw new IOException("URL blocked by rewriter: " + url);
            }
            url = rewritten;
        }


        /*init request builder*/
        okhttp3.Request.Builder okRequestBuilder = new okhttp3.Request.Builder().url(url)
                .cacheControl(new CacheControl.Builder()
                        .maxAge(0, TimeUnit.SECONDS)
                        .build());

        /*set request headers*/
        HashMap<String, String> map = new HashMap<String, String>();
        map.putAll(request.getHeaders());
        map.putAll(additionalHeaders);
        for (String headerName : map.keySet()) {
            okRequestBuilder.addHeader(headerName, map.get(headerName));
        }


        /*set request method*/
        setRequestMethod(okRequestBuilder, request);

        okhttp3.Request okRequest = okRequestBuilder.build();


        /*init request client*/
        int timeoutMs = request.getTimeoutMs();
        OkHttpClient.Builder okClientBuilder = new OkHttpClient.Builder();
        okClientBuilder.followRedirects(HttpURLConnection.getFollowRedirects())
                .followSslRedirects(HttpURLConnection.getFollowRedirects())
                .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                .readTimeout(timeoutMs, TimeUnit.MILLISECONDS);

        if (okRequest.isHttps() && mSslSocketFactory != null) {
            okClientBuilder.sslSocketFactory(mSslSocketFactory);
        }

        // Initialize HttpResponse with data from the HttpURLConnection.
        ProtocolVersion protocolVersion = new ProtocolVersion("HTTP", 1, 1);


        OkHttpClient okClient = okClientBuilder.build();

        Response okResponse = okClient.newCall(okRequest).execute();
        int responseCode = okResponse.code();
        if (responseCode == -1) {
            // -1 is returned by getResponseCode() if the response code could not be retrieved.
            // Signal to the caller that something was wrong with the connection.
            throw new IOException("Could not retrieve response code from HttpUrlConnection.");
        }
        StatusLine responseStatus = new BasicStatusLine(protocolVersion,
                responseCode, okResponse.message());
        BasicHttpResponse response = new BasicHttpResponse(responseStatus);
        if (hasResponseBody(request.getMethod(), responseStatus.getStatusCode())) {
            response.setEntity(entityFromOkHttp(okResponse));
        }

        for (int i = 0; i < okResponse.headers().size(); i++) {
            String name = okResponse.headers().name(i);
            String value = okResponse.headers().value(i);
            Header h = new BasicHeader(name, value);
            response.addHeader(h);
        }
        return response;
    }

    /**
     * Checks if a response message contains a body.
     *
     * @param requestMethod request method
     * @param responseCode  response status code
     * @return whether the response has a body
     * @see <a href="https://tools.ietf.org/html/rfc7230#section-3.3">RFC 7230 section 3.3</a>
     */

    private static boolean hasResponseBody(int requestMethod, int responseCode) {
        return requestMethod != Request.Method.HEAD
                && !(HttpStatus.SC_CONTINUE <= responseCode && responseCode < HttpStatus.SC_OK)
                && responseCode != HttpStatus.SC_NO_CONTENT
                && responseCode != HttpStatus.SC_NOT_MODIFIED;
    }

    /**
     * Initializes an {@link HttpEntity} from the given {@link HttpURLConnection}.
     *
     * @return an HttpEntity populated with data from <code>connection</code>.
     */
    private static HttpEntity entityFromOkHttp(Response okResponse) {
        ResponseBody rb = okResponse.body();

        BasicHttpEntity entity = new BasicHttpEntity();
        InputStream inputStream = rb.byteStream();
        entity.setContent(inputStream);
        entity.setContentLength(rb.contentLength());
        entity.setContentType(rb.contentType().type());
        return entity;
    }

    private void setRequestMethod(okhttp3.Request.Builder builder,
                                  Request<?> request) throws IOException, AuthFailureError {
        switch (request.getMethod()) {
            case Method.DEPRECATED_GET_OR_POST:
                throw new IllegalStateException("unRecognize request method Method.DEPRECATED_GET_OR_POST");
            case Method.GET:
                builder.get();
                break;
            case Method.DELETE:
                builder.delete();
                break;
            case Method.POST:
                RequestBody body = checkRequestBody(request);
                if (body != null) {
                    builder.post(body);
                }
                break;
            case Method.PUT:
                body = checkRequestBody(request);
                if (body != null) {
                    builder.put(body);
                }
                break;
            case Method.HEAD:
                builder.head();
                break;
            case Method.OPTIONS:
                throw new IllegalStateException("unRecognize request method Method.OPTIONS");
            case Method.TRACE:
                throw new IllegalStateException("unRecognize request method Method.TRACE");
            case Method.PATCH:
                body = checkRequestBody(request);
                if (body != null) {
                    builder.patch(body);
                }
                break;
            default:
                throw new IllegalStateException("Unknown method type.");
        }
    }

    private static RequestBody checkRequestBody(Request<?> request)
            throws IOException, AuthFailureError {
        byte[] body = request.getBody();
        if (body != null) {
            RequestBody requestBody = RequestBody.create(MediaType.parse(request.getBodyContentType()), body);
            return requestBody;
        }

        return null;
    }
}
