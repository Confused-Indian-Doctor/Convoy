package com.convoy.offline;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

/** Bounded, namespace-aware GPX parsing. Caller owns the input; call away from the UI thread. */
public final class GpxParser {
    public static final int MAX_BYTES = 16 * 1024 * 1024;
    public static final int MAX_POINTS = 100000;
    public static final int MAX_WAYPOINTS = 2000;
    public static final int MAX_SEGMENTS = 10000;
    public static final int MAX_DEPTH = 64;
    private static final Pattern UNSAFE_XML = Pattern.compile("<!\\s*(DOCTYPE|ENTITY)", Pattern.CASE_INSENSITIVE);
    private static final Pattern ENCODING = Pattern.compile("<\\?xml[^?]*encoding\\s*=\\s*['\"]([^'\"]+)['\"]", Pattern.CASE_INSENSITIVE);
    private GpxParser() {}

    public static GpxRoute parse(InputStream input) throws IOException { return parse(input, "Imported GPX route"); }

    public static GpxRoute parse(InputStream input, String fallbackName) throws IOException {
        String xml = decode(readBounded(input));
        // Android SAX providers differ in feature support. This preflight is mandatory, not best effort.
        // No DTD/entity declaration can reach the parser even when its hardening features are unavailable.
        if (UNSAFE_XML.matcher(xml).find()) throw new IOException("GPX must not contain DTDs or entity declarations");
        Handler handler = new Handler(fallbackName);
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(true); factory.setValidating(false);
            try { factory.setXIncludeAware(false); } catch (UnsupportedOperationException ignored) {}
            feature(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true);
            feature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
            feature(factory, "http://xml.org/sax/features/external-general-entities", false);
            feature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
            feature(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            XMLReader reader = factory.newSAXParser().getXMLReader();
            reader.setEntityResolver((publicId, systemId) -> { throw new SAXException("External GPX entities are forbidden"); });
            reader.setContentHandler(handler); reader.setErrorHandler(handler);
            reader.parse(new InputSource(new StringReader(xml)));
            return handler.result();
        } catch (Exception e) {
            if (e instanceof InterruptedIOException) throw (InterruptedIOException)e;
            throw new IOException("Could not import this GPX file: " + safeMessage(e), e);
        }
    }

    static byte[] readBounded(InputStream input) throws IOException {
        if (input == null) throw new IOException("Could not read that GPX file");
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buffer = new byte[65536];
        int n, total = 0;
        while ((n = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("GPX import cancelled");
            if (n == 0) { int one = input.read(); if (one == -1) break; buffer[0] = (byte)one; n = 1; }
            if (n > MAX_BYTES - total) throw new IOException("GPX file exceeds the 16 MB limit");
            total += n; out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }

    private static String decode(byte[] bytes) throws IOException {
        Charset charset = StandardCharsets.UTF_8; int start = 0;
        if (bytes.length >= 2 && bytes[0] == (byte)0xff && bytes[1] == (byte)0xfe) {
            charset = StandardCharsets.UTF_16LE; start = 2;
        } else if (bytes.length >= 2 && bytes[0] == (byte)0xfe && bytes[1] == (byte)0xff) {
            charset = StandardCharsets.UTF_16BE; start = 2;
        } else if (bytes.length >= 4 && bytes[0] == 0 && bytes[1] == '<' && bytes[2] == 0 && bytes[3] == '?') {
            charset = StandardCharsets.UTF_16BE;
        } else if (bytes.length >= 4 && bytes[0] == '<' && bytes[1] == 0 && bytes[2] == '?' && bytes[3] == 0) {
            charset = StandardCharsets.UTF_16LE;
        } else if (bytes.length >= 3 && bytes[0] == (byte)0xef && bytes[1] == (byte)0xbb && bytes[2] == (byte)0xbf) start = 3;
        try {
            String xml = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.length - start)).toString();
            Matcher declaration = ENCODING.matcher(xml.substring(0, Math.min(xml.length(), 1024)));
            if (declaration.find()) {
                String encoding = declaration.group(1).toUpperCase(Locale.ROOT).replace("-", "");
                if (!encoding.equals("UTF8") && !encoding.equals("UTF16") && !encoding.equals("UTF16LE") && !encoding.equals("UTF16BE"))
                    throw new IOException("GPX encoding must be UTF-8 or UTF-16");
            }
            return xml;
        } catch (CharacterCodingException e) { throw new IOException("GPX text encoding is invalid", e); }
    }

    private static void feature(SAXParserFactory factory, String name, boolean enabled) {
        try { factory.setFeature(name, enabled); } catch (Exception ignored) {
            // Bounded decoded preflight and a rejecting EntityResolver remain enforced on Android.
        }
    }
    private static String safeMessage(Exception e) {
        String message = e.getMessage(); return GpxRoute.clean(message == null ? "Invalid GPX" : message, 160);
    }

    private static final class Tag {
        final String name; final boolean gpx;
        Tag(String name, boolean gpx) { this.name = name; this.gpx = gpx; }
    }

    private static final class Handler extends DefaultHandler {
        final ArrayDeque<Tag> stack = new ArrayDeque<>();
        final ArrayList<GpxRoute.Segment> segments = new ArrayList<>();
        final ArrayList<GpxRoute.Waypoint> waypoints = new ArrayList<>();
        final String fallback;
        String routeName = "", metadataName = "", capture = "", captureParent = "";
        final StringBuilder text = new StringBuilder();
        ArrayList<GpxRoute.Point> currentSegment;
        Candidate candidate;
        int pointCount, captureDepth, captureLimit, segmentCount;
        boolean rootSeen;
        Handler(String fallback) { this.fallback = GpxRoute.clean(fallback, 120); }

        @Override public void startElement(String uri, String local, String qName, Attributes attrs) throws SAXException {
            if (Thread.currentThread().isInterrupted()) throw new SAXException("GPX import cancelled");
            String name = local.isEmpty() ? qName : local;
            boolean known = uri.isEmpty() || uri.equals("http://www.topografix.com/GPX/1/0") || uri.equals("http://www.topografix.com/GPX/1/1");
            Tag parent = stack.peek();
            if (stack.size() >= MAX_DEPTH) throw new SAXException("GPX XML nesting is too deep");
            if (parent == null) {
                if (rootSeen || !known || !name.equals("gpx")) throw new SAXException("This document is not GPX");
                rootSeen = true;
            }
            stack.push(new Tag(name, known));
            if (!known || parent == null || !parent.gpx) return;
            String parentName = parent.name;
            if (name.equals("trkseg")) {
                if (!parentName.equals("trk") || currentSegment != null) throw new SAXException("Invalid GPX track segment");
                beginSegment();
            } else if (name.equals("rte")) {
                if (!parentName.equals("gpx") || currentSegment != null) throw new SAXException("Invalid GPX route");
                beginSegment();
            } else if (name.equals("trkpt") || name.equals("rtept") || name.equals("wpt")) {
                boolean trackPoint = name.equals("trkpt") && parentName.equals("trkseg");
                boolean routePoint = name.equals("rtept") && parentName.equals("rte");
                boolean waypoint = name.equals("wpt") && parentName.equals("gpx");
                if ((!trackPoint && !routePoint && !waypoint) || candidate != null) throw new SAXException("Invalid GPX point structure");
                double lat = coordinate(attrs.getValue("lat")), lon = coordinate(attrs.getValue("lon"));
                if (!GpxRoute.validCoordinate(lat, lon)) throw new SAXException("GPX contains an invalid coordinate");
                candidate = new Candidate(lat, lon, name);
                if (trackPoint || routePoint) {
                    if (currentSegment == null || ++pointCount > MAX_POINTS) throw new SAXException("GPX exceeds 100,000 track/route points");
                    currentSegment.add(new GpxRoute.Point(lat, lon));
                }
            } else if ((name.equals("name") || name.equals("desc") || name.equals("cmt") || name.equals("type") || name.equals("sym")) &&
                    (parentName.equals("trk") || parentName.equals("rte") || parentName.equals("metadata") ||
                            parentName.equals("wpt") || parentName.equals("rtept") || parentName.equals("trkpt"))) {
                capture = name; captureParent = parentName; captureDepth = stack.size();
                captureLimit = name.equals("desc") || name.equals("cmt") ? 2048 : 120; text.setLength(0);
            }
        }

        private void beginSegment() throws SAXException {
            if (++segmentCount > MAX_SEGMENTS) throw new SAXException("GPX contains too many segments");
            currentSegment = new ArrayList<>();
        }
        private double coordinate(String value) throws SAXException {
            try { return Double.parseDouble(value); } catch (Exception e) { throw new SAXException("GPX point is missing a valid coordinate"); }
        }
        @Override public void characters(char[] chars, int start, int length) {
            if (!capture.isEmpty() && stack.size() == captureDepth && text.length() < captureLimit)
                text.append(chars, start, Math.min(length, captureLimit - text.length()));
        }
        @Override public void endElement(String uri, String local, String qName) throws SAXException {
            Tag tag = stack.peek(); if (tag == null) throw new SAXException("Invalid GPX structure");
            String name = tag.name;
            if (tag.gpx && !capture.isEmpty() && stack.size() == captureDepth && name.equals(capture)) {
                String value = GpxRoute.clean(text.toString(), captureLimit);
                if (capture.equals("name") && (captureParent.equals("trk") || captureParent.equals("rte")) && routeName.isEmpty()) routeName = value;
                else if (capture.equals("name") && captureParent.equals("metadata") && metadataName.isEmpty()) metadataName = value;
                else if (candidate != null && captureParent.equals(candidate.tag)) {
                    if (capture.equals("name")) candidate.name = value;
                    else if (capture.equals("desc") || capture.equals("cmt") && candidate.description.isEmpty()) candidate.description = value;
                    else if (capture.equals("type")) candidate.type = value;
                    else if (capture.equals("sym")) candidate.symbol = value;
                }
                capture = ""; text.setLength(0);
            }
            if (tag.gpx && candidate != null && name.equals(candidate.tag)) {
                if (name.equals("wpt") || name.equals("rtept") && !candidate.name.isEmpty()) {
                    if (waypoints.size() >= MAX_WAYPOINTS) throw new SAXException("GPX exceeds 2,000 waypoints");
                    waypoints.add(new GpxRoute.Waypoint(candidate.lat, candidate.lon, candidate.name,
                            candidate.description, candidate.type, candidate.symbol));
                }
                candidate = null;
            }
            if (tag.gpx && (name.equals("trkseg") || name.equals("rte"))) {
                if (currentSegment == null) throw new SAXException("Invalid GPX segment");
                if (!currentSegment.isEmpty()) segments.add(new GpxRoute.Segment(currentSegment));
                currentSegment = null;
            }
            stack.pop();
        }
        @Override public void warning(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
        @Override public void error(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
        @Override public void fatalError(org.xml.sax.SAXParseException e) throws SAXException { throw e; }
        GpxRoute result() throws SAXException {
            if (!rootSeen || !stack.isEmpty() || segments.isEmpty()) throw new SAXException("No usable GPX track or route was found");
            String name = !routeName.isEmpty() ? routeName : !metadataName.isEmpty() ? metadataName : fallback;
            return new GpxRoute(name, segments, waypoints);
        }
    }
    private static final class Candidate {
        final double lat, lon; final String tag;
        String name = "", description = "", type = "", symbol = "";
        Candidate(double lat, double lon, String tag) { this.lat = lat; this.lon = lon; this.tag = tag; }
    }
}
