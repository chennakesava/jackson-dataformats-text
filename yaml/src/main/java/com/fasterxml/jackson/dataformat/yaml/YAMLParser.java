package com.fasterxml.jackson.dataformat.yaml;

import java.io.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Optional;
import java.util.regex.Pattern;

import com.fasterxml.jackson.core.*;
import org.snakeyaml.engine.v1.api.LoadSettings;
import org.snakeyaml.engine.v1.api.LoadSettingsBuilder;
import org.snakeyaml.engine.v1.common.Anchor;
import org.snakeyaml.engine.v1.events.AliasEvent;
import org.snakeyaml.engine.v1.events.CollectionStartEvent;
import org.snakeyaml.engine.v1.events.Event;
import org.snakeyaml.engine.v1.events.MappingStartEvent;
import org.snakeyaml.engine.v1.events.NodeEvent;
import org.snakeyaml.engine.v1.events.ScalarEvent;
import org.snakeyaml.engine.v1.exceptions.Mark;
import org.snakeyaml.engine.v1.nodes.Tag;
import org.snakeyaml.engine.v1.parser.ParserImpl;
import org.snakeyaml.engine.v1.resolver.JsonScalarResolver;
import org.snakeyaml.engine.v1.resolver.ScalarResolver;
import org.snakeyaml.engine.v1.scanner.StreamReader;

import com.fasterxml.jackson.core.base.ParserBase;
import com.fasterxml.jackson.core.io.IOContext;
import com.fasterxml.jackson.core.json.JsonReadContext;
import com.fasterxml.jackson.core.util.BufferRecycler;

/**
 * {@link JsonParser} implementation used to expose YAML documents
 * in form that allows other Jackson functionality to process YAML content,
 * such as binding POJOs to and from it, and building tree representations.
 */
public class YAMLParser extends ParserBase
{
    // 04-Feb-2018, tatu: None defined yet so...
    /**
     * Enumeration that defines all togglable features for YAML parsers.
    public enum Feature implements FormatFeature
    {
        ;

        final boolean _defaultState;
        final int _mask;

        // Method that calculates bit set (flags) of all features that
        // are enabled by default.
        public static int collectDefaults()
        {
            int flags = 0;
            for (Feature f : values()) {
                if (f.enabledByDefault()) {
                    flags |= f.getMask();
                }
            }
            return flags;
        }

        private Feature(boolean defaultState) {
            _defaultState = defaultState;
            _mask = (1 << ordinal());
        }

        @Override
        public boolean enabledByDefault() { return _defaultState; }
        @Override
        public boolean enabledIn(int flags) { return (flags & _mask) != 0; }
        @Override
        public int getMask() { return _mask; }
    }
    */

    // note: does NOT include '0', handled separately
//    private final static Pattern PATTERN_INT = Pattern.compile("-?[1-9][0-9]*");

    /**
     * We will use pattern that is bit stricter than YAML definition,
     * but we will still allow things like extra '_' in there.
     */
    private final static Pattern PATTERN_FLOAT = Pattern.compile(
            "[-+]?([0-9][0-9_]*)?\\.[0-9]*([eE][-+][0-9]+)?");
    
    /*
    /**********************************************************************
    /* Configuration
    /**********************************************************************
     */

//    protected int _formatFeatures;

    /*
    /**********************************************************************
    /* Input sources
    /**********************************************************************
     */

    /**
     * Need to keep track of underlying {@link Reader} to be able to
     * auto-close it (if required to)
     */
    protected final Reader _reader;

    protected final ParserImpl _yamlParser;
    protected final ScalarResolver _yamlResolver = new JsonScalarResolver();

    /*
    /**********************************************************************
    /* State
    /**********************************************************************
     */

    /**
     * Keep track of the last event read, to get access to Location info
     */
    protected Event _lastEvent;

    /**
     * We need to keep track of text values.
     */
    protected String _textValue;

    /**
     * For some tokens (specifically, numbers), we'll have cleaned up version,
     * mostly free of underscores
     */
    protected String _cleanedTextValue;

    /**
     * Let's also have a local copy of the current field name
     */
    protected String _currentFieldName;

    /**
     * Flag that is set when current token was derived from an Alias
     * (reference to another value's anchor)
     */
    protected boolean _currentIsAlias;

    /**
     * Anchor for the value that parser currently points to: in case of
     * structured types, value whose first token current token is.
     */
    protected Optional<Anchor> _currentAnchor;
    
    /*
    /**********************************************************************
    /* Life-cycle
    /**********************************************************************
     */

    public YAMLParser(ObjectReadContext readCtxt, IOContext ioCtxt,
            BufferRecycler br, int parserFeatures, Reader reader)
    {
        super(readCtxt, ioCtxt, parserFeatures);
//        _formatFeatures = formatFeatures;
        _reader = reader;
        LoadSettings settings = new LoadSettingsBuilder().build();//TODO use parserFeatures
        _yamlParser = new ParserImpl(new StreamReader(reader, settings), settings);
    }

    /*                                                                                       
    /**********************************************************                              
    /* Extended YAML-specific API
    /**********************************************************                              
     */

    /**
     * Method that can be used to check whether current token was
     * created from YAML Alias token (reference to an anchor).
     */
    public boolean isCurrentAlias() {
        return _currentIsAlias;
    }

    /**
     * Method that can be used to check if the current token has an
     * associated anchor (id to reference via Alias)
     *
     * deprecated Since 2.3 (was added in 2.1) -- use {@link #getObjectId} instead
    public String getCurrentAnchor() {
        return _currentAnchor;
    }
    */
    
    /*                                                                                       
    /**********************************************************                              
    /* Versioned                                                                             
    /**********************************************************                              
     */

    @Override
    public Version version() {
        return PackageVersion.VERSION;
    }

    /*
    /**********************************************************                              
    /* ParserBase method impls
    /**********************************************************                              
     */

    @Override
    public Reader getInputSource() {
        return _reader;
    }

    @Override
    protected void _closeInput() throws IOException {
        /* 25-Nov-2008, tatus: As per [JACKSON-16] we are not to call close()
         *   on the underlying Reader, unless we "own" it, or auto-closing
         *   feature is enabled.
         *   One downside is that when using our optimized
         *   Reader (granted, we only do that for UTF-32...) this
         *   means that buffer recycling won't work correctly.
         */
        if (_ioContext.isResourceManaged() || isEnabled(StreamReadFeature.AUTO_CLOSE_SOURCE)) {
            _reader.close();
        }
    }

    /*
    /**********************************************************                              
    /* FormatFeature support (none yet)
    /**********************************************************                              
     */

    /*
    @Override
    public int getFormatFeatures() {
        return _formatFeatures;
    }
    */

//    @Override public CsvSchema getSchema() 
    
    /*
    /**********************************************************
    /* Location info
    /**********************************************************
     */

    @Override
    public JsonLocation getTokenLocation()
    {
        if (_lastEvent == null) {
            return JsonLocation.NA;
        }
        return _locationFor(_lastEvent.getStartMark());
    }

    @Override
    public JsonLocation getCurrentLocation() {
        // can assume we are at the end of token now...
        if (_lastEvent == null) {
            return JsonLocation.NA;
        }
        return _locationFor(_lastEvent.getEndMark());
    }

    protected JsonLocation _locationFor(Optional<Mark> option)
    {
        if (!option.isPresent()) {
            return new JsonLocation(_ioContext.getSourceReference(),
                    -1, -1, -1);
        }
        Mark m = option.get();
        return new JsonLocation(_ioContext.getSourceReference(),
                -1,
                m.getLine() + 1, // from 0- to 1-based
                m.getColumn() + 1); // ditto
    }

    // Note: SHOULD override 'getTokenLineNr', 'getTokenColumnNr', but those are final in 2.0

    /*
    /**********************************************************
    /* Parsing
    /**********************************************************
     */

    @Override
    public JsonToken nextToken() throws IOException
    {
        _currentIsAlias = false;
        _binaryValue = null;
        if (_closed) {
            return null;
        }

        while (true /*_yamlParser.hasNext()*/) {
            Event evt;
            try {
                evt = _yamlParser.next();
            } catch (org.snakeyaml.engine.v1.exceptions.YamlEngineException e) {
                throw new JacksonYAMLParseException(this, e.getMessage(), e);
            }
            // is null ok? Assume it is, for now, consider to be same as end-of-doc
            if (evt == null) {
                _currentAnchor = Optional.empty();
                return (_currToken = null);
            }
            _lastEvent = evt;
            // One complication: field names are only inferred from the fact that we are
            // in Object context; they are just ScalarEvents (but separate and NOT just tagged
            // on values)
            if (_parsingContext.inObject()) {
                if (_currToken != JsonToken.FIELD_NAME) {
                    if (evt.getEventId() != Event.ID.Scalar) {
                        _currentAnchor = Optional.empty();
                        // end is fine
                        if (evt.getEventId() == Event.ID.MappingEnd) {
                            if (!_parsingContext.inObject()) { // sanity check is optional, but let's do it for now
                                _reportMismatchedEndMarker('}', ']');
                            }
                            _parsingContext = _parsingContext.getParent();
                            return (_currToken = JsonToken.END_OBJECT);
                        }
                        _reportError("Expected a field name (Scalar value in YAML), got this instead: "+evt);
                    }
                    // 20-Feb-2019, tatu: [dataformats-text#123] Looks like YAML exposes Anchor for Object at point
                    //   where we return START_OBJECT (which makes sense), but, alas, Jackson expects that at point
                    //   after first FIELD_NAME. So we will need to defer clearing of the anchor slightly,
                    //   just for the very first entry; and only if no anchor for name found.
                    //  ... not even 100% sure this is correct, or robust, but does appear to work for specific
                    //  test case given.
                    final ScalarEvent scalar = (ScalarEvent) evt;
                    final Optional<Anchor> newAnchor = scalar.getAnchor();
                    if (newAnchor.isPresent() || (_currToken != JsonToken.START_OBJECT)) {
                        _currentAnchor = scalar.getAnchor();
                    }
                    final String name = scalar.getValue();
                    _currentFieldName = name;
                    _parsingContext.setCurrentName(name);
                    return (_currToken = JsonToken.FIELD_NAME);
                }
            } else if (_parsingContext.inArray()) {
                _parsingContext.expectComma();
            }

            _currentAnchor = Optional.empty();

            switch (evt.getEventId()) {
                case Scalar:
                    // scalar values are probably the commonest:
                    JsonToken t = _decodeScalar((ScalarEvent) evt);
                    _currToken = t;
                    return t;
                case MappingStart:
                    // followed by maps, then arrays
                    Optional<Mark> m = evt.getStartMark();
                    MappingStartEvent map = (MappingStartEvent) evt;
                    _currentAnchor = map.getAnchor();
                    _parsingContext = _parsingContext.createChildObjectContext(
                            m.map(mark -> mark.getLine()).orElse(0), m.map(mark -> mark.getColumn()).orElse(0));
                    return (_currToken = JsonToken.START_OBJECT);

                case MappingEnd:
                    // actually error; can not have map-end here
                    _reportError("Not expecting END_OBJECT but a value");

                case SequenceStart:
                    Optional<Mark> mrk = evt.getStartMark();
                    _currentAnchor = ((NodeEvent) evt).getAnchor();
                    _parsingContext = _parsingContext.createChildArrayContext(
                            mrk.map(mark -> mark.getLine()).orElse(0), mrk.map(mark -> mark.getColumn()).orElse(0));
                    return (_currToken = JsonToken.START_ARRAY);

                case SequenceEnd:
                    if (!_parsingContext.inArray()) { // sanity check is optional, but let's do it for now
                        _reportMismatchedEndMarker(']', '}');
                    }
                    _parsingContext = _parsingContext.getParent();
                    return (_currToken = JsonToken.END_ARRAY);

                // after this, less common tokens:
                case DocumentEnd:
                    // [dataformat-yaml#72]: logical end of doc; fine. Two choices; either skip,
                    // or return null as marker (but do NOT close). Earlier returned `null`, but
                    // to allow multi-document reading should actually just skip.
                    // return (_currToken = null);
                    continue;

                case DocumentStart:
                    // DocumentStartEvent dd = (DocumentStartEvent) evt;
                    // does this matter? Shouldn't, should it?
                    continue;

                case Alias:
                    AliasEvent alias = (AliasEvent) evt;
                    _currentIsAlias = true;
                    _textValue = alias.getAnchor().orElseThrow(() -> new RuntimeException("Alias must be provided.")).getAnchor();
                    _cleanedTextValue = null;
                    // for now, nothing to do: in future, maybe try to expose as ObjectIds?
                    return (_currToken = JsonToken.VALUE_STRING);

                case StreamEnd:
                    // end-of-input; force closure
                    close();
                    return (_currToken = null);

                case StreamStart:
                    // useless, skip
                    continue;
            }
        }
    }

    protected JsonToken _decodeScalar(ScalarEvent scalar) throws IOException
    {
        String value = scalar.getValue();
        _textValue = value;
        _cleanedTextValue = null;
        // we may get an explicit tag, if so, use for corroborating...
        Optional<String> typeTagOptional = scalar.getTag();
        final int len = value.length();

        if (!typeTagOptional.isPresent() || typeTagOptional.get().equals("!")) { // no, implicit
            Tag nodeTag = _yamlResolver.resolve(value, scalar.getImplicit().canOmitTagInPlainScalar());
            if (nodeTag == Tag.STR) {
                return JsonToken.VALUE_STRING;
            }
            if (nodeTag == Tag.INT) {
                return _decodeNumberScalar(value, len);
            }
            if (nodeTag == Tag.FLOAT) {
                _numTypesValid = 0;
                return _cleanYamlFloat(value);
            }
            if (nodeTag == Tag.BOOL) {
                Boolean B = _matchYAMLBoolean(value, len);
                if (B != null) {
                    return B ? JsonToken.VALUE_TRUE : JsonToken.VALUE_FALSE;
                }
            } else if (nodeTag == Tag.NULL) {
                return JsonToken.VALUE_NULL;
            } else {
                // what to do with timestamp and binary and merge etc.
                return JsonToken.VALUE_STRING;
            }
        } else { // yes, got type tag
            String typeTag = typeTagOptional.get();
            if (typeTag.startsWith("tag:yaml.org,2002:")) {
                typeTag = typeTag.substring("tag:yaml.org,2002:".length());
                if (typeTag.contains(",")) {
                    typeTag = typeTag.split(",")[0];
                }
            }
            // [dataformats-text#39]: support binary type
            if ("binary".equals(typeTag)) {
                // 15-Dec-2017, tatu: 2.9.4 uses Jackson's codec because SnakeYAML does
                //    not export its codec via OSGi (breaking 2.9.3). Note that trailing
                //    whitespace is ok with core 2.9.4, but not earlier, so we'll trim
                //    on purpose here
                value = value.trim();
                try {
                    _binaryValue = Base64Variants.MIME.decode(value);
                } catch (IllegalArgumentException e) {
                    _reportError(e.getMessage());
                }
                return JsonToken.VALUE_EMBEDDED_OBJECT;
            }
            // canonical values by YAML are actually 'y' and 'n'; but plenty more unofficial:
            if ("bool".equals(typeTag)) { // must be "true" or "false"
                Boolean B = _matchYAMLBoolean(value, len);
                if (B != null) {
                    return B ? JsonToken.VALUE_TRUE : JsonToken.VALUE_FALSE;
                }
            } else {
                if ("int".equals(typeTag)) {
                    return _decodeNumberScalar(value, len);
                }
                if ("float".equals(typeTag)) {
                    _numTypesValid = 0;
                    return _cleanYamlFloat(value);
                }
                if ("null".equals(typeTag)) {
                    return JsonToken.VALUE_NULL;
                }
            }
        }

        // any way to figure out actual type? No?
        return JsonToken.VALUE_STRING;
    }

    protected Boolean _matchYAMLBoolean(String value, int len)
    {
        switch (len) {
        case 4:
            //TODO it should be only lower case
            if ("true".equalsIgnoreCase(value)) return Boolean.TRUE;
            break;
        case 5:
            if ("false".equalsIgnoreCase(value)) return Boolean.FALSE;
            break;
        }
        return null;
    }

    protected JsonToken _decodeNumberScalar(String value, final int len)
    {
        if ("0".equals(value)) { // special case for regexp (can't take minus etc)
            _numberNegative = false;
            _numberInt = 0;
            _numTypesValid = NR_INT;
            return JsonToken.VALUE_NUMBER_INT;
        }
        /* 05-May-2012, tatu: Turns out this is a hot spot; so let's write it
         *   out and avoid regexp overhead...
         */
        //if (PATTERN_INT.matcher(value).matches()) {
        int i;
        char sign = value.charAt(0);
        if (sign == '-') {
            _numberNegative = true;
            if (len == 1) {
                return null;
            }
            i = 1;
        } else if (sign == '+') {
            _numberNegative = false;
            if (len == 1) {
                return null;
            }
            i = 1;
        } else {
            _numberNegative = false;
            i = 0;
        }
        // !!! 11-Jan-2018, tatu: Should check for binary/octal/hex/sexagesimal
        //    as per http://yaml.org/type/int.html

        int underscores = 0;
        while (true) {
            int c = value.charAt(i);
            if (c > '9' || c < '0') {
                if (c != '_') {
                    break;
                }
                ++underscores;
            }
            if (++i == len) {
                _numTypesValid = 0;
                if (underscores > 0) {
                    return _cleanYamlInt(_textValue);
                }
                _cleanedTextValue = _textValue;
                return JsonToken.VALUE_NUMBER_INT;
            }
        }
        if (PATTERN_FLOAT.matcher(value).matches()) {
            _numTypesValid = 0;
            return _cleanYamlFloat(_textValue);
        }

        // 25-Aug-2016, tatu: If we can't actually match it to valid number,
        //    consider String; better than claiming there's not toekn
        return JsonToken.VALUE_STRING;
    }

    protected JsonToken _decodeIntWithUnderscores(String value, final int len)
    {
        return JsonToken.VALUE_NUMBER_INT;
    }
    
    /*
    /**********************************************************
    /* String value handling
    /**********************************************************
     */

    // For now we do not store char[] representation...
    @Override
    public boolean hasTextCharacters() {
        return false;
    }

    @Override
    public String getText() throws IOException
    {
        if (_currToken == JsonToken.VALUE_STRING) {
            return _textValue;
        }
        if (_currToken == JsonToken.FIELD_NAME) {
            return _currentFieldName;
        }
        if (_currToken != null) {
            if (_currToken.isScalarValue()) {
                return _textValue;
            }
            return _currToken.asString();
        }
        return null;
    }

    @Override
    public String currentName() throws IOException
    {
        if (_currToken == JsonToken.FIELD_NAME) {
            return _currentFieldName;
        }
        return super.currentName();
    }

    @Override
    public char[] getTextCharacters() throws IOException {
        String text = getText();
        return (text == null) ? null : text.toCharArray();
    }

    @Override
    public int getTextLength() throws IOException {
        String text = getText();
        return (text == null) ? 0 : text.length();
    }

    @Override
    public int getTextOffset() throws IOException {
        return 0;
    }

    @Override // since 2.8
    public int getText(Writer writer) throws IOException
    {
        String str = getText();
        if (str == null) {
            return 0;
        }
        writer.write(str);
        return str.length();
    }

    /*
    /**********************************************************************
    /* Binary (base64)
    /**********************************************************************
     */

    @Override
    public Object getEmbeddedObject() throws IOException {
        if (_currToken == JsonToken.VALUE_EMBEDDED_OBJECT) {
            return _binaryValue;
        }
        return null;
    }

    // Base impl from `ParserBase` works fine here:
//    public byte[] getBinaryValue(Base64Variant variant) throws IOException

    @Override
    public int readBinaryValue(Base64Variant b64variant, OutputStream out) throws IOException
    {
        byte[] b = getBinaryValue(b64variant);
        out.write(b);
        return b.length;
    }

    /*
    /**********************************************************************
    /* Number accessor overrides
    /**********************************************************************
     */

    @Override
    protected void _parseNumericValue(int expType) throws IOException
    {
        // Int or float?
        if (_currToken == JsonToken.VALUE_NUMBER_INT) {
            int len = _textValue.length();
            if (_numberNegative) {
                len--;
            }
            if (len <= 9) { // definitely fits in int
                _numberInt = Integer.parseInt(_textValue);
                _numTypesValid = NR_INT;
                return;
            }
            if (len <= 18) { // definitely fits AND is easy to parse using 2 int parse calls
                long l = Long.parseLong(_cleanedTextValue);
                // [JACKSON-230] Could still fit in int, need to check
                if (len == 10) {
                    if (_numberNegative) {
                        if (l >= Integer.MIN_VALUE) {
                            _numberInt = (int) l;
                            _numTypesValid = NR_INT;
                            return;
                        }
                    } else {
                        if (l <= Integer.MAX_VALUE) {
                            _numberInt = (int) l;
                            _numTypesValid = NR_INT;
                            return;
                        }
                    }
                }
                _numberLong = l;
                _numTypesValid = NR_LONG;
                return;
            }
            // !!! TODO: implement proper bounds checks; now we'll just use BigInteger for convenience
            try {
                BigInteger n = new BigInteger(_cleanedTextValue);
                // Could still fit in a long, need to check
                if (len == 19 && n.bitLength() <= 63) {
                    _numberLong = n.longValue();
                    _numTypesValid = NR_LONG;
                    return;
                }
                _numberBigInt = n;
                _numTypesValid = NR_BIGINT;
                return;
            } catch (NumberFormatException nex) {
                // NOTE: pass non-cleaned variant for error message
                // Can this ever occur? Due to overflow, maybe?
                _wrapError("Malformed numeric value '" + _textValue + "'", nex);
            }
        }
        if (_currToken == JsonToken.VALUE_NUMBER_FLOAT) {
            // strip out optional underscores, if any:
            final String str = _cleanedTextValue;
            try {
                if (expType == NR_BIGDECIMAL) {
                    _numberBigDecimal = new BigDecimal(str);
                    _numTypesValid = NR_BIGDECIMAL;
                } else {
                    // Otherwise double has to do
                    _numberDouble = Double.parseDouble(str);
                    _numTypesValid = NR_DOUBLE;
                }
            } catch (NumberFormatException nex) {
                // Can this ever occur? Due to overflow, maybe?
                // NOTE: pass non-cleaned variant for error message
                _wrapError("Malformed numeric value '" + _textValue + "'", nex);
            }
            return;
        }
        _reportError("Current token (" + _currToken + ") not numeric, can not use numeric value accessors");
    }

    @Override
    protected int _parseIntValue() throws IOException
    {
        if (_currToken == JsonToken.VALUE_NUMBER_INT) {
            int len = _cleanedTextValue.length();
            if (_numberNegative) {
                len--;
            }
            if (len <= 9) { // definitely fits in int
                _numTypesValid = NR_INT;
                return (_numberInt = Integer.parseInt(_cleanedTextValue));
            }
        }
        _parseNumericValue(NR_INT);
        if ((_numTypesValid & NR_INT) == 0) {
            convertNumberToInt();
        }
        return _numberInt;
    }

    /*
    /**********************************************************************
    /* Native id (type id) access
    /**********************************************************************
     */

    @Override
    public boolean canReadObjectId() { // yup
        return true;
    }

    @Override
    public boolean canReadTypeId() {
        return true; // yes, YAML got 'em
    }

    @Override
    public String getObjectId() throws IOException
    {
        return _currentAnchor.map(a -> a.getAnchor()).orElse(null);
    }

    @Override
    public String getTypeId() throws IOException
    {
        Optional<String> tagOpt;
        if (_lastEvent instanceof CollectionStartEvent) {
            tagOpt = ((CollectionStartEvent) _lastEvent).getTag();
        } else if (_lastEvent instanceof ScalarEvent) {
            tagOpt = ((ScalarEvent) _lastEvent).getTag();
        } else {
            return null;
        }
        if (tagOpt.isPresent()) {
            String tag = tagOpt.get();
            // 04-Aug-2013, tatu: Looks like YAML parser's expose these in... somewhat exotic
            //   ways sometimes. So let's prepare to peel off some wrappings:
            while (tag.startsWith("!")) {
                tag = tag.substring(1);
            }
            return tag;
        }
        return null;
    }
    
    /*
    /**********************************************************************
    /* Internal methods
    /**********************************************************************
     */

    /**
     * Helper method used to clean up YAML floating-point value so it can be parsed
     * using standard JDK classes.
     * Currently this just means stripping out optional underscores.
     */
    private JsonToken _cleanYamlInt(String str)
    {
        // Here we already know there is either plus sign, or underscore (or both) so
        final int len = str.length();
        StringBuilder sb = new StringBuilder(len);
        // first: do we have a leading plus sign to skip?
        int i = (str.charAt(0) == '+') ? 1 : 0;
        for (; i < len; ++i) {
            char c = str.charAt(i);
            if (c != '_') {
                sb.append(c);
            }
        }
        _cleanedTextValue = sb.toString();
        return JsonToken.VALUE_NUMBER_INT;
    }

    private JsonToken _cleanYamlFloat(String str)
    {
        // Here we do NOT yet know whether we might have underscores so check
        final int len = str.length();
        int ix = str.indexOf('_');
        if (ix < 0 || len == 0) {
            _cleanedTextValue = str;
            return JsonToken.VALUE_NUMBER_FLOAT;
        }
        StringBuilder sb = new StringBuilder(len);
        // first: do we have a leading plus sign to skip?
        int i = (str.charAt(0) == '+') ? 1 : 0;
        for (; i < len; ++i) {
            char c = str.charAt(i);
            if (c != '_') {
                sb.append(c);
            }
        }
        _cleanedTextValue = sb.toString();
        return JsonToken.VALUE_NUMBER_FLOAT;
    }

    // Promoted from `ParserBase` in 3.0
    protected void _reportMismatchedEndMarker(int actCh, char expCh) throws JsonParseException {
        JsonReadContext ctxt = getParsingContext();
        _reportError(String.format(
                "Unexpected close marker '%s': expected '%c' (for %s starting at %s)",
                (char) actCh, expCh, ctxt.typeDesc(), ctxt.getStartLocation(_getSourceReference())));
    }
}
