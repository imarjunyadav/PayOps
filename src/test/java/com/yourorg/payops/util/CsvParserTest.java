package com.yourorg.payops.util;

import com.yourorg.payops.exception.BusinessRuleException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvParserTest {

    @Test
    void parseSimpleCsv() throws IOException {
        String csv = "col1,col2,col3\nval1,val2,val3\n";
        List<List<String>> records = CsvParser.parse(new StringReader(csv));

        assertThat(records).hasSize(2);
        assertThat(records.get(0)).containsExactly("col1", "col2", "col3");
        assertThat(records.get(1)).containsExactly("val1", "val2", "val3");
    }

    @Test
    void parseQuotedFieldsWithCommas() throws IOException {
        String csv = "\"Payment Reference\",\"Amount\",\"Note\"\n\"PAY-001\",\"1,000.50\",\"Fee, included\"\n";
        List<List<String>> records = CsvParser.parse(new StringReader(csv));

        assertThat(records).hasSize(2);
        assertThat(records.get(0)).containsExactly("Payment Reference", "Amount", "Note");
        assertThat(records.get(1)).containsExactly("PAY-001", "1,000.50", "Fee, included");
    }

    @Test
    void parseQuotedFieldsWithEscapedQuotes() throws IOException {
        String csv = "ref,desc\nPAY-1,\"Quoted \"\"value\"\" here\"\n";
        List<List<String>> records = CsvParser.parse(new StringReader(csv));

        assertThat(records).hasSize(2);
        assertThat(records.get(1).get(1)).isEqualTo("Quoted \"value\" here");
    }

    @Test
    void parseQuotedFieldsWithNewlines() throws IOException {
        String csv = "ref,multiline\nPAY-1,\"line 1\nline 2\"\nPAY-2,singleline\n";
        List<List<String>> records = CsvParser.parse(new StringReader(csv));

        assertThat(records).hasSize(3);
        assertThat(records.get(1).get(1)).isEqualTo("line 1\nline 2");
        assertThat(records.get(2).get(1)).isEqualTo("singleline");
    }

    @Test
    void parseHandlesWindowsCrlfAndMixedLineEndings() throws IOException {
        String csv = "a,b\r\n1,2\r\n3,4\n5,6\r";
        List<List<String>> records = CsvParser.parse(new StringReader(csv));

        assertThat(records).hasSize(4);
        assertThat(records.get(1)).containsExactly("1", "2");
        assertThat(records.get(2)).containsExactly("3", "4");
        assertThat(records.get(3)).containsExactly("5", "6");
    }

    @Test
    void parseThrowsOnUnclosedQuote() {
        String csv = "ref,amount\nPAY-1,\"100.00";
        assertThatThrownBy(() -> CsvParser.parse(new StringReader(csv)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("unclosed quote");
    }

    @Test
    void parseThrowsOnUnexpectedQuoteInUnquotedField() {
        String csv = "ref,amount\nPAY-\"1,100.00";
        assertThatThrownBy(() -> CsvParser.parse(new StringReader(csv)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("unexpected quote character inside unquoted field");
    }

    @Test
    void parseThrowsOnUnexpectedCharactersAfterQuote() {
        String csv = "ref,amount\n\"PAY-1\"extra,100.00";
        assertThatThrownBy(() -> CsvParser.parse(new StringReader(csv)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("unexpected character");
    }

    @Test
    void escapeNullAndEmpty() {
        assertThat(CsvParser.escape(null)).isEmpty();
        assertThat(CsvParser.escape("")).isEmpty();
        assertThat(CsvParser.escape("hello")).isEqualTo("hello");
    }

    @Test
    void escapeWithSpecialCharacters() {
        assertThat(CsvParser.escape("hello, world")).isEqualTo("\"hello, world\"");
        assertThat(CsvParser.escape("hello \"world\"")).isEqualTo("\"hello \"\"world\"\"\"");
        assertThat(CsvParser.escape("line1\nline2")).isEqualTo("\"line1\nline2\"");
        assertThat(CsvParser.escape("line1\r\nline2")).isEqualTo("\"line1\r\nline2\"");
    }
}
