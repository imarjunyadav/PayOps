package com.yourorg.payops.util;

import com.yourorg.payops.exception.BusinessRuleException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * RFC 4180 compliant CSV parser and formatter.
 * <p>
 * Supports:
 * <ul>
 *   <li>Quoted fields enclosing commas, newlines, and carriage returns</li>
 *   <li>Escaped quotes inside quoted fields ("")</li>
 *   <li>Unquoted fields</li>
 *   <li>CRLF (\r\n), LF (\n), and CR (\r) line breaks</li>
 * </ul>
 * Rejects malformed CSV inputs (unclosed quotes, stray quotes in unquoted fields,
 * unexpected characters after closing quotes).
 */
public final class CsvParser {

    private enum State {
        START_OF_FIELD,
        IN_QUOTES,
        AFTER_QUOTE,
        UNQUOTED_FIELD
    }

    private CsvParser() {}

    /**
     * Parses the CSV character stream into a list of records, where each record
     * is a list of column values.
     *
     * @param inputReader the input reader
     * @return list of parsed records
     * @throws BusinessRuleException if the CSV is malformed (unclosed quote, invalid characters)
     * @throws IOException on I/O failure
     */
    public static List<List<String>> parse(Reader inputReader) throws IOException {
        List<List<String>> records = new ArrayList<>();
        BufferedReader reader = (inputReader instanceof BufferedReader)
                ? (BufferedReader) inputReader
                : new BufferedReader(inputReader);

        StringBuilder currentField = new StringBuilder();
        List<String> currentRecord = new ArrayList<>();
        State state = State.START_OF_FIELD;
        int lineNumber = 1;
        int quoteStartLine = 1;

        int ch;
        while ((ch = reader.read()) != -1) {
            char c = (char) ch;

            switch (state) {
                case START_OF_FIELD:
                    if (c == ' ' || c == '\t') {
                        // Skip leading whitespace before field
                        continue;
                    }
                    if (c == '"') {
                        state = State.IN_QUOTES;
                        quoteStartLine = lineNumber;
                    } else if (c == ',') {
                        currentRecord.add("");
                    } else if (c == '\r') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next != '\n' && next != -1) {
                            reader.reset();
                        }
                        lineNumber++;
                        if (!currentRecord.isEmpty() || currentField.length() > 0) {
                            currentRecord.add("");
                            records.add(currentRecord);
                            currentRecord = new ArrayList<>();
                        }
                    } else if (c == '\n') {
                        lineNumber++;
                        if (!currentRecord.isEmpty() || currentField.length() > 0) {
                            currentRecord.add("");
                            records.add(currentRecord);
                            currentRecord = new ArrayList<>();
                        }
                    } else {
                        currentField.append(c);
                        state = State.UNQUOTED_FIELD;
                    }
                    break;

                case IN_QUOTES:
                    if (c == '"') {
                        // Check if escaped quote ""
                        reader.mark(1);
                        int next = reader.read();
                        if (next == '"') {
                            currentField.append('"');
                        } else {
                            if (next != -1) {
                                reader.reset();
                            }
                            state = State.AFTER_QUOTE;
                        }
                    } else {
                        if (c == '\n') {
                            lineNumber++;
                        }
                        currentField.append(c);
                    }
                    break;

                case AFTER_QUOTE:
                    if (c == ' ' || c == '\t') {
                        // Allowed whitespace after closing quote
                        continue;
                    }
                    if (c == ',') {
                        currentRecord.add(currentField.toString());
                        currentField.setLength(0);
                        state = State.START_OF_FIELD;
                    } else if (c == '\r') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next != '\n' && next != -1) {
                            reader.reset();
                        }
                        lineNumber++;
                        currentRecord.add(currentField.toString());
                        currentField.setLength(0);
                        records.add(currentRecord);
                        currentRecord = new ArrayList<>();
                        state = State.START_OF_FIELD;
                    } else if (c == '\n') {
                        lineNumber++;
                        currentRecord.add(currentField.toString());
                        currentField.setLength(0);
                        records.add(currentRecord);
                        currentRecord = new ArrayList<>();
                        state = State.START_OF_FIELD;
                    } else {
                        throw new BusinessRuleException(
                                "Malformed CSV at line " + lineNumber + ": unexpected character '" + c + "' after closing quote");
                    }
                    break;

                case UNQUOTED_FIELD:
                    if (c == '"') {
                        throw new BusinessRuleException(
                                "Malformed CSV at line " + lineNumber + ": unexpected quote character inside unquoted field");
                    } else if (c == ',') {
                        currentRecord.add(currentField.toString().trim());
                        currentField.setLength(0);
                        state = State.START_OF_FIELD;
                    } else if (c == '\r') {
                        reader.mark(1);
                        int next = reader.read();
                        if (next != '\n' && next != -1) {
                            reader.reset();
                        }
                        lineNumber++;
                        currentRecord.add(currentField.toString().trim());
                        currentField.setLength(0);
                        records.add(currentRecord);
                        currentRecord = new ArrayList<>();
                        state = State.START_OF_FIELD;
                    } else if (c == '\n') {
                        lineNumber++;
                        currentRecord.add(currentField.toString().trim());
                        currentField.setLength(0);
                        records.add(currentRecord);
                        currentRecord = new ArrayList<>();
                        state = State.START_OF_FIELD;
                    } else {
                        currentField.append(c);
                    }
                    break;
            }
        }

        if (state == State.IN_QUOTES) {
            throw new BusinessRuleException(
                    "Malformed CSV: unclosed quote starting at line " + quoteStartLine);
        }

        // Flush any remaining field
        if (state == State.AFTER_QUOTE || state == State.UNQUOTED_FIELD || currentField.length() > 0 || !currentRecord.isEmpty()) {
            if (state == State.AFTER_QUOTE) {
                currentRecord.add(currentField.toString());
            } else if (state == State.UNQUOTED_FIELD || currentField.length() > 0) {
                currentRecord.add(currentField.toString().trim());
            }
            if (!currentRecord.isEmpty() && !(currentRecord.size() == 1 && currentRecord.get(0).isEmpty())) {
                records.add(currentRecord);
            }
        }

        return records;
    }

    /**
     * Escapes a value for inclusion in a CSV according to RFC 4180 rules.
     * Encloses in quotes if the value contains comma, double quote, newline, or carriage return.
     * Escapes internal double quotes by doubling them ("").
     *
     * @param value raw value
     * @return safely escaped CSV field string
     */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        if (value.contains(",") || value.contains("\"") || value.contains("\n") || value.contains("\r")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
