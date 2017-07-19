/*
 * Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.wso2.extension.siddhi.map.text.sourcemapper;

import org.apache.log4j.Logger;
import org.wso2.siddhi.annotation.Example;
import org.wso2.siddhi.annotation.Extension;
import org.wso2.siddhi.annotation.Parameter;
import org.wso2.siddhi.annotation.util.DataType;
import org.wso2.siddhi.core.config.SiddhiAppContext;
import org.wso2.siddhi.core.event.Event;
import org.wso2.siddhi.core.stream.input.source.AttributeMapping;
import org.wso2.siddhi.core.stream.input.source.InputEventHandler;
import org.wso2.siddhi.core.stream.input.source.SourceMapper;
import org.wso2.siddhi.core.util.AttributeConverter;
import org.wso2.siddhi.core.util.config.ConfigReader;
import org.wso2.siddhi.core.util.transport.OptionHolder;
import org.wso2.siddhi.query.api.annotation.Annotation;
import org.wso2.siddhi.query.api.annotation.Element;
import org.wso2.siddhi.query.api.definition.Attribute;
import org.wso2.siddhi.query.api.definition.StreamDefinition;
import org.wso2.siddhi.query.api.exception.SiddhiAppValidationException;
import org.wso2.siddhi.query.api.util.AnnotationHelper;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

//import org.wso2.siddhi.query.api.util.AnnotationHelper;

/**
 * This mapper converts Text string input to {@link org.wso2.siddhi.core.event.ComplexEventChunk}. This extension
 * accepts optional regex expressions to select specific attributes from the stream.
 */
@Extension(
        name = "text",
        namespace = "sourceMapper",
        description = "Text to siddhi event source mapper. Transports which accepts text messages can utilize this " +
                "extension"
                + "to convert the incoming text message to Siddhi event. Users can either use the onEventHandler," +
                " a pre-defined text format, where event conversion will happen without any configs or regex to " +
                "map a text message using custom configs.",
        parameters = {
                @Parameter(name = "regex.groupid",
                        description =
                                "Used to specify a regular expression group. Here, groupid can be any capital letter " +
                                        "such as regex.A,regex.B .. etc. User can specify any number of regular " +
                                        "expression groups and in the attribute annotation,user needs to map all " +
                                        "attributes to the regular expression group with the matching group index. " +
                                        "If the user needs to enable custom mapping, it is " +
                                        "mandatory to specify matching group for each and every attribute.",
                        type = {DataType.STRING}),

                @Parameter(name = "fail.on.missing.attribute",
                        description = "This can either have a value of 'true' or 'false'. By default it will be " +
                                "true. This attribute allows user to handle unknown attributes. By default if a regex "
                                + "execution fails or attribute is not present in the event, SP will drop that " +
                                "message. However setting this property to false will prompt DAS to pass 'null' " +
                                "value to that attribute in the event.",
                        defaultValue = "true",
                        optional = true,
                        type = {DataType.BOOL}),

                @Parameter(name = "event.grouping.enabled",
                        description =
                                "This attribute is used to specify whether the event group is enabled or not. " +
                                        "If the user needs to receive group of events together and generate multiple " +
                                        "siddhi events user can enable this by specifying true.",
                        type = {DataType.BOOL},
                        optional = true,
                        defaultValue = "false"),

                @Parameter(name = "delimiter",
                        description = "This attribute indicates the delimiter of grouped event which is " +
                                "expected to be received. This should be whole line and not a single character.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "~~~~~~~~~~"),
                @Parameter(name = "new.line.character",
                        description = "This attribute indicates the new line character of the event which is " +
                                "expected to be received. This is used mostly when communication between 2 types of " +
                                "operating systems is expected. For instance as the end of line character " +
                                "linux uses '\n' while windows use '\r\n'.",
                        type = {DataType.STRING},
                        optional = true,
                        defaultValue = "\n")
        },
        examples = {
                @Example(
                        syntax = "@source(type='inMemory', topic='stock', @map(type='text'))\n"
                                + "define stream FooStream (symbol string, price float, volume long);\n",
                        description = "Above configuration will perform a default text input mapping." +
                                " Expected input should be as follows,"

                                + "symbol:\"WSO2\",\n"
                                + "price:55.6,\n"
                                + "volume:100"

                                + "OR"

                                + "symbol:'WSO2',\n"
                                + "price:55.6,\n"
                                + "volume:100"

                                + "If group events is enabled then input should be as follows,"

                                + "symbol:'WSO2',\n"
                                + "price:55.6,\n"
                                + "volume:100\n"
                                + "~~~~~~~~~~\n"
                                + "symbol:'WSO2',\n"
                                + "price:55.6,\n"
                                + "volume:100"
                ),
                @Example(
                        syntax = "@source(type='inMemory', topic='stock', @map(type='text', fail.on.unknown.attribute" +
                                " = 'true', regex.A='(\\w+)\\s([-0-9]+)',regex.B='volume\\s([-0-9]+)', @attributes(" +
                                "symbol = 'A[1]'," +
                                "price = 'A[2]'," +
                                "volume = 'B' )",
                        description = "Above configuration will perform a custom text mapping. Expected output " +
                                "would be as follows,"
                                + "wos2 550 volume 100"
                )
        }
)
public class TextSourceMapper extends SourceMapper {
    private static final Logger log = Logger.getLogger(TextSourceMapper.class);
    private static final String FAIL_ON_MISSING_ATTRIBUTE = "fail.on.missing.attribute";
    private static final String OPTION_GROUP_EVENTS = "event.grouping.enabled";
    private static final String OPTION_NEW_LINE = "new.line.character";
    private static final String REGULAR_EXPRESSION_GROUP = "regex.";
    private static final String OPTION_GROUP_EVENTS_DELIMITER = "delimiter";
    private static final String DEFAULT_NEW_LINE = "\n";
    private static final String DEFAULT_DELIMITER = "~~~~~~~~~~";
    private static final String DEFAULT_EVENT_GROUP = "false";
    private static final String DEFAULT_FALLON_MISSING_ATTRIBUTE = "true";
    private static final String REGEX_GROUP_OPENING_ELEMENT = "[";
    private static final String REGEX_GROUP_CLOSING_ELEMENT = "]";
    private static final String KEY_VALUE_SEPARATOR = ":";
    private static final String ATTRIBUTE_SEPARATOR = ",";
    private static final String EMPTY_STRING = "";
    private static final String REGEX_GROUP_SPLIT_REGEX_ELEMENT = "\\[";
    private static final String STRING_ENCLOSING_ELEMENT = "\"";
    private Map<String, Attribute.Type> attributeTypeMap = new HashMap<>();
    private Map<String, Integer> attributePositionMap = new HashMap<>();
    private Map<String, String> regexGroupMap = new HashMap<>();

    private List<Element> attributeRegex;
    private List<Attribute> attributeList;
    private AttributeConverter attributeConverter;
    private boolean isCustomMappingEnabled = false;
    private boolean eventGroupEnabled = false;
    private boolean failOnMissingAttribute;
    private StreamDefinition streamDefinition;
    private String eventDelimiter;
    private String endOfLine;
    private BitSet assignedPositionsBitSet;
    private String streamID;
    /**
     * Initialize the mapper and the mapping configurations.
     *
     * @param streamDefinition the  StreamDefinition
     * @param optionHolder     mapping options
     * @param attributeMappingList             list of attributes mapping
     * @param configReader     source config reader.
     */
    @Override
    public void init(StreamDefinition streamDefinition, OptionHolder optionHolder,
                     List<AttributeMapping> attributeMappingList,
                     ConfigReader configReader, SiddhiAppContext siddhiAppContext) {
        this.streamID = streamDefinition.getId();
        this.attributeConverter = new AttributeConverter();
        this.streamDefinition = streamDefinition;
        this.attributeList = streamDefinition.getAttributeList();
        this.attributeTypeMap = new HashMap<>(attributeList.size());
        this.attributePositionMap = new HashMap<>(attributeList.size());
        this.eventGroupEnabled = Boolean.valueOf(optionHolder
                .validateAndGetStaticValue(OPTION_GROUP_EVENTS, DEFAULT_EVENT_GROUP));
        this.endOfLine = optionHolder.validateAndGetStaticValue(OPTION_NEW_LINE, DEFAULT_NEW_LINE);
        this.eventDelimiter = optionHolder.validateAndGetStaticValue(OPTION_GROUP_EVENTS_DELIMITER,
                DEFAULT_DELIMITER) + endOfLine;
        this.failOnMissingAttribute = Boolean.parseBoolean(optionHolder.validateAndGetStaticValue
                (FAIL_ON_MISSING_ATTRIBUTE,
                        DEFAULT_FALLON_MISSING_ATTRIBUTE));
        for (Attribute attribute : attributeList) {
            attributeTypeMap.put(attribute.getName(), attribute.getType());
            attributePositionMap.put(attribute.getName(), streamDefinition.getAttributePosition(attribute.getName()));
        }
        if (attributeMappingList != null && attributeMappingList.size() > 0) { //custom mapping scenario
            this.isCustomMappingEnabled = true;
            List<Annotation> sourceAnnotation = AnnotationHelper.getAnnotation("source", streamDefinition
                    .getAnnotations()).getAnnotations();
            List<Annotation> mapAnnotation = AnnotationHelper.getAnnotation("map", sourceAnnotation).getAnnotations();
            Annotation attributeAnnotation = AnnotationHelper.getAnnotation("attributes", mapAnnotation);
            this.attributeRegex = attributeAnnotation.getElements();
            for (Element el : streamDefinition.getAnnotations().get(0).getAnnotations().get(0).getElements()) {
                if (el.getKey().contains(REGULAR_EXPRESSION_GROUP)) {
                    regexGroupMap.put(el.getKey()
                            .replaceFirst(REGULAR_EXPRESSION_GROUP, EMPTY_STRING), el.getValue());
                }
            }
            if (attributeRegex.size() != attributeMappingList.size()) {
                throw new SiddhiAppValidationException("Stream: '" + streamDefinition.getId() + "' has "
                        + streamDefinition.getAttributeList().size() + " attributes, but here we only found" +
                        " below mapping." + attributeMappingList.toString() + "All the attributes should" +
                        " have mapping" + " in the stream " + streamID + " of siddhi text input mapper.");
            }
            if (streamDefinition.getAttributeList().size() < attributeMappingList.size()) {
                throw new SiddhiAppValidationException("Stream: '" + streamDefinition.getId() + "' has "
                        + streamDefinition.getAttributeList().size() + " attributes, but " + attributeMappingList.size()
                        + " attribute mappings found. Each attribute should have one and only one mapping" + " in " +
                        "the stream " + streamID + " of siddhi text input mapper.");
            }
        }
        this.assignedPositionsBitSet = new BitSet(attributePositionMap.size());
    }

    /**
     * Receives an event as an text string from {@link org.wso2.siddhi.core.stream.input.source.Source}, converts it to
     * a {@link org.wso2.siddhi.core.event.ComplexEventChunk} and onEventHandler.
     *
     * @param eventObject                 the input event, given as an text string
     * @param inputEventHandler input handler
     */
    @Override
    protected void mapAndProcess(Object eventObject, InputEventHandler inputEventHandler) throws InterruptedException {
        if (!eventGroupEnabled) {
            onEventHandler(inputEventHandler, eventObject);
        } else {
            String[] allEvents = String.valueOf(eventObject).split(eventDelimiter);
            int i;
            for (i = 0; i < allEvents.length - 1; i++) {
                onEventHandler(inputEventHandler, allEvents[i].substring(0, allEvents[i].length() -
                        endOfLine.length()));
            }
            onEventHandler(inputEventHandler, allEvents[i]);
        }
    }

    @Override
    public Class[] getSupportedInputEventClasses() {
        return new Class[]{String.class};
    }

    /**
     * Receives an event as an text string from {@link org.wso2.siddhi.core.stream.input.source.Source}, converts it to
     * a {@link org.wso2.siddhi.core.event.ComplexEventChunk} and onEventHandler.
     *
     * @param eventObject       the input event, given as an text string
     * @param inputEventHandler input handler
     */
    private void onEventHandler(InputEventHandler inputEventHandler, Object eventObject) {
        Event[] result;
        try {
            if (isCustomMappingEnabled) {
                result = convertToCustomEvents(String.valueOf(eventObject));
            } else {
                result = convertToDefaultEvents(String.valueOf(eventObject));
            }
            if (result.length != 0) {
                inputEventHandler.sendEvents(result);
            }
        } catch (Throwable e) {
            log.error("Exception occurred when converting Text message:" + eventObject + " to Siddhi Event "+
                    "in the stream " + streamID + " of siddhi text input mapper.", e);
        }
    }

    /**
     * Match the given text against the regex and return the group defined by the group index.
     *
     * @param text the input text
     * @return matched output
     */
    private String match(String text, int groupIndex, String regex) {
        String matchedText;
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            matchedText = matcher.group(groupIndex);
        } else {
            matchedText = null;
        }
        return matchedText;
    }

    /**
     * Converts an event from an text string to {@link Event}.
     *
     * @param eventObject The input event, given as an text string
     * @return the constructed {@link Event} object
     */
    private Event[] convertToCustomEvents(Object eventObject) {
        AtomicBoolean isValidEvent = new AtomicBoolean();
        isValidEvent.set(true);
        List<Event> eventList = new ArrayList<>();
        Event event = new Event(this.streamDefinition.getAttributeList().size());
        Object[] data = event.getData();
        if (isCustomMappingEnabled) {   //custom mapping case
            for (Element attr : attributeRegex) {
                String matchText = null;
                String attributeValue = attr.getValue();
                String attributeKey = attr.getKey();
                if (attributeValue.contains(REGEX_GROUP_OPENING_ELEMENT) && attributeValue.contains
                        (REGEX_GROUP_CLOSING_ELEMENT)) { //symbol=A[1]
                    String[] regexGroupElements = attr.getValue().replace(REGEX_GROUP_CLOSING_ELEMENT, EMPTY_STRING)
                            .split(REGEX_GROUP_SPLIT_REGEX_ELEMENT, 2);
                    String regexGroup = regexGroupElements[0];
                    int regexPosition = Integer.parseInt(regexGroupElements[1]);
                    String regex = regexGroupMap.get(regexGroup);
                    if (regex != null) {
                        try {
                            matchText = match((String) eventObject,
                                    regexPosition, regexGroupMap.get(regexGroup));
                        } catch (IndexOutOfBoundsException e) {
                            log.error("Could not find group for " + regexPosition + " in the stream "
                                    + streamID + " of siddhi text input mapper.", e);
                            isValidEvent.set(false);
                        }
                    } else {
                        log.error("Could not find machine regular expression group for " + regexGroup + " for " +
                                "attribute " + attributeKey + " in the stream " + streamID + " of siddhi text " +
                                "input mapper.");
                        isValidEvent.set(false);
                    }
                } else { //symbol=B
                    String regex = regexGroupMap.get(attributeValue);
                    if (regex != null) {
                        try {
                            matchText = match((String) eventObject,
                                    1, regexGroupMap.get(attributeValue));
                        } catch (IndexOutOfBoundsException e) {
                            log.error("Could not find regular expression group index for " + attributeValue
                                    + " in the stream " + streamID + " of siddhi text input mapper.", e);
                            isValidEvent.set(false);
                        }
                    } else {
                        log.error("Could not find machine regular expression group for " + attributeValue + " for " +
                                "attribute " + attributeKey + " in the stream " + streamID + " of siddhi text " +
                                "input mapper.");
                        isValidEvent.set(false);
                    }
                }
                if (failOnMissingAttribute && (matchText == null)) { //if fail on missing attribute is enabled
                    log.error("Invalid format of event " + eventObject + " for " +
                            "attribute " + attr + " could not find proper value while fail on missing attribute is " +
                            "'true' in the stream " + streamID + " of siddhi text input mapper.");
                    isValidEvent.set(false);
                }
                int position = attributePositionMap.get(attributeKey);
                if ((Attribute.Type.STRING != attributeTypeMap.get(attributeKey)) && (matchText != null)) {
                    data[position] = attributeConverter.getPropertyValue(matchText.replaceAll(",", ""), attributeTypeMap
                            .get(attributeKey));
                } else {
                    data[position] = matchText;
                }
            }
        }
        eventList.add(event);
        return eventList.toArray(new Event[0]);
    }

    private Event[] convertToDefaultEvents(String eventObject) {
        AtomicBoolean isValidEvent = new AtomicBoolean();
        isValidEvent.set(true);
        List<Event> eventList = new ArrayList<>();
        Event event = new Event(this.streamDefinition.getAttributeList().size());
        Object[] data = event.getData();
        String[] events = eventObject.split(ATTRIBUTE_SEPARATOR + endOfLine);
        if ((events.length < attributeList.size()) && (failOnMissingAttribute)) {
            log.error("Invalid format of event because some required attributes are missing in event " + eventObject
                    + " while needed attributes are " + attributeList.toString() + " in the stream " + streamID +
                    " of siddhi text input mapper.");
            isValidEvent.set(false);
        }
        for (String event1 : events) {
            String[] eventObjects = event1.split(KEY_VALUE_SEPARATOR, 2);
            //remove trim
            if (eventObjects.length == 2) {
                String key = eventObjects[0].trim();
                String value = eventObjects[1].trim();
                Integer position = attributePositionMap.get(key.trim());
                //to get 1st mach only
                if (position != null) {
                    if (!assignedPositionsBitSet.get(position)) {
                        try {
                            Attribute.Type attributeType = attributeTypeMap.get(key.trim());
                            if (attributeType != Attribute.Type.STRING) {
                                data[position] = attributeConverter.getPropertyValue(value.replaceAll(","
                                        , "").trim(), attributeType);
                            } else {
                                data[position] = value.trim().substring(STRING_ENCLOSING_ELEMENT.length(),
                                        +value.length() - STRING_ENCLOSING_ELEMENT.length());
                            }
                        } catch (ClassCastException | NumberFormatException e) {
                            log.error("Incompatible data format. Because value is " + value + " and attribute type is "
                                    + attributeTypeMap.get(key.trim()).name() + " in the stream " + streamID +
                                    " of siddhi text input mapper.");
                            isValidEvent.set(false);
                        }
                        assignedPositionsBitSet.flip(position);
                    }
                }
            }
        }
        if ((events.length > assignedPositionsBitSet.length()) && (assignedPositionsBitSet.length()
                < attributeList.size()) && (failOnMissingAttribute)) {
            log.error("Invalid format of event because some required attributes are missing while some un necessary " +
                    "mappings are present" + eventObject + " in the stream " + streamID +
                    " of siddhi text input mapper.");
            isValidEvent.set(false);
        }
        assignedPositionsBitSet.clear();
        if (isValidEvent.get()) {
            eventList.add(event);
        }
        return eventList.toArray(new Event[0]);
    }
}
