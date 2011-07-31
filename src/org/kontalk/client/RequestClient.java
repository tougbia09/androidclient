package org.kontalk.client;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.kontalk.crypto.Coder;
import org.kontalk.service.RequestListener;
import org.kontalk.ui.MessagingPreferences;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xmlpull.v1.XmlSerializer;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.util.Xml;


/**
 * A generic request endpoint client.
 * @author Daniele Ricci
 * @version 1.0
 */
public class RequestClient extends AbstractClient {

    public RequestClient(Context context, EndpointServer server, String token) {
        super(context, server, token);
    }

    public List<StatusResponse> message(final String[] group, final String mime,
                final byte[] content, final MessageSender job, final RequestListener listener)
            throws IOException {

        try {
            byte[] toMessage = null;
            String toMime = null;
            Coder coder = null;
            // check if we have to encrypt the message
            if (job.getEncryptKey() != null) {
                coder = MessagingPreferences.getEncryptCoder(job.getEncryptKey());
                if (coder != null) {
                    toMessage = coder.encrypt(content);
                    toMime = AbstractMessage.ENC_MIME_PREFIX + mime;
                }
            }

            if (coder == null) {
                toMessage = content;
                toMime = mime;
            }

            // http request!
            currentRequest = mServer.prepareMessage(job, listener, mAuthToken, group, toMime,
                new ByteArrayInputStream(toMessage), toMessage.length);
            return execute();
        }
        catch (Exception e) {
            throw innerException("post message error", e);
        }
        finally {
            currentRequest = null;
        }
    }

    public List<StatusResponse> message(final String[] group, final String mime, final Uri uri,
            final Context context, final MessageSender job, final RequestListener listener)
                throws IOException {

        try {
            AssetFileDescriptor stat = context.getContentResolver().openAssetFileDescriptor(uri, "r");
            long length = stat.getLength();
            InputStream in = context.getContentResolver().openInputStream(uri);

            InputStream toMessage = null;
            String toMime = null;
            long toLength = 0;
            Coder coder = null;
            // check if we have to encrypt the message
            if (job.getEncryptKey() != null) {
                coder = MessagingPreferences.getEncryptCoder(job.getEncryptKey());
                if (coder != null) {
                    toMessage = coder.wrapInputStream(in);
                    toMime = AbstractMessage.ENC_MIME_PREFIX + mime;
                    toLength = Coder.getEncryptedLength(length);
                }
            }

            if (coder == null) {
                toMessage = in;
                toMime = mime;
                toLength = length;
            }

            // http request!
            currentRequest = mServer.prepareMessage(job, listener, mAuthToken, group, toMime, toMessage, toLength);
            return execute();
        }
        catch (Exception e) {
            throw innerException("post message error", e);
        }
        finally {
            currentRequest = null;
        }
    }

    /**
     * Sends a request to the server.
     * @throws IOException
     */
    public List<StatusResponse> request(final String cmd, final List<NameValuePair> params,
            final byte[] content) throws IOException {

        try {
            // http request!
            currentRequest = mServer.prepareRequest(cmd, params, mAuthToken, content);
            return execute();
        }
        catch (Exception e) {
            throw innerException("request error", e);
        }
        finally {
            currentRequest = null;
        }
    }

    public List<StatusResponse> lookup(final String[] userIds) throws IOException {
        String xmlData = buildLookupXMLData(userIds);
        return request("lookup", null, xmlData.getBytes());
    }

    private String buildLookupXMLData(String[] lookupNumbers) throws IOException {
        String xmlContent = null;
        // build xml in a proper manner
        XmlSerializer xml = Xml.newSerializer();
        StringWriter xmlString = new StringWriter();

        xml.setOutput(xmlString);
        xml.startDocument("UTF-8", Boolean.TRUE);
        xml
            .startTag(null, "body");

        for (String data : lookupNumbers) {
            xml
                .startTag(null, "u")
                .text(data)
                .endTag(null, "u");
        }

        xml
            .endTag(null, "body")
            .endDocument();

        xmlContent = xmlString.toString();
        xmlString.close();

        return xmlContent;
    }

    @SuppressWarnings("unchecked")
    private List<StatusResponse> execute() throws Exception {
        List<StatusResponse> list = null;
        try {
            // http request!
            HttpResponse response = mServer.execute(currentRequest);

            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();

            /*
            FOR TESTING PURPOSES
            String xmlContent = EntityUtils.toString(response.getEntity());
            Log.e("AAAAHHH!!!", xmlContent);
            StringReader reader = new StringReader(xmlContent);
            InputSource inputSource = new InputSource(reader);

            Document doc = builder.parse(inputSource);
            reader.close();
            */

            Document doc = builder.parse(response.getEntity().getContent());
            Element body = doc.getDocumentElement();
            NodeList children = body.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node node = (Node) children.item(i);
                if ("s".equals(node.getNodeName())) {
                    String errcode = null;
                    Map<String, Object> extra = null;
                    // status!
                    NodeList msgChildren = node.getChildNodes();
                    for (int j = 0; j < msgChildren.getLength(); j++) {
                        Element n2 = (Element) msgChildren.item(j);

                        // error code
                        if ("e".equals(n2.getNodeName())) {
                            errcode = n2.getFirstChild().getNodeValue();
                        }
                        // other data
                        else {
                            String key = n2.getNodeName();
                            // TODO handle empty nodes
                            String value = n2.getFirstChild().getNodeValue();
                            if (extra == null) {
                                extra = new HashMap<String, Object>(1);
                                extra.put(key, value);
                            }
                            else {
                                Object old = extra.get(key);
                                // no old value - single value
                                if (old == null) {
                                    extra.put(key, value);
                                }
                                // old single value - transform to array
                                else if (!(old instanceof List<?>)) {
                                    List<String> newObj = new ArrayList<String>(1);
                                    newObj.add((String) old);
                                    newObj.add(value);
                                    extra.put(key, newObj);
                                }
                                // old multiple values - add to list
                                else {
                                    List<String> newObj = (List<String>) old;
                                    newObj.add(value);
                                }
                            }
                        }
                    }

                    if (errcode != null) {
                        // add the status to the list
                        StatusResponse status = null;
                        try {
                            status = new StatusResponse(Integer.parseInt(errcode));
                            status.extra = extra;
                        }
                        catch (Exception e) {}

                        if (status != null) {
                            if (list == null)
                                list = new ArrayList<StatusResponse>();
                            list.add(status);
                        }
                    }
                }
            }
        }
        finally {
            currentRequest = null;
        }

        return list;
    }

    private IOException innerException(String detail, Throwable cause) {
        IOException ie = new IOException(detail);
        ie.initCause(cause);
        return ie;
    }
}
