/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python;

import com.intellij.openapi.util.TextRange;
import com.intellij.testFramework.UsefulTestCase;
import com.jetbrains.python.inspections.PyNewStyleStringFormatParser;
import com.jetbrains.python.inspections.PyNewStyleStringFormatParser.Field;
import com.jetbrains.python.inspections.PyNewStyleStringFormatParser.ParseResult;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static com.jetbrains.python.inspections.PyStringFormatParser.*;

/**
 * @author yole
 */
public class PyStringFormatParserTest extends UsefulTestCase {
  public void testSimple() {
    List<FormatStringChunk> chunks = parsePercentFormat("abc");
    assertEquals(1, chunks.size());
    assertConstant(chunks.get(0), 0, 3);
  }

  private static void assertConstant(FormatStringChunk aChunk, final int start, final int end) {
    ConstantChunk chunk = (ConstantChunk)aChunk;
    assertEquals(start, chunk.getStartIndex());
    assertEquals(end, chunk.getEndIndex());
  }

  public void testDoublePercent() {
    List<FormatStringChunk> chunks = parsePercentFormat("abc%%def");
    assertEquals(1, chunks.size());
    assertConstant(chunks.get(0), 0, 8);
  }

  public void testFormat() {
    List<FormatStringChunk> chunks = parsePercentFormat("%s");
    assertEquals(1, chunks.size());
    SubstitutionChunk chunk = (SubstitutionChunk)chunks.get(0);
    assertEquals(0, chunk.getStartIndex());
    assertEquals(2, chunk.getEndIndex());
    assertEquals('s', chunk.getConversionType());
  }

  public void testSubstitutionAfterFormat() {
    List<FormatStringChunk> chunks = parsePercentFormat("Hello, %s");
    assertEquals(2, chunks.size());
    assertConstant(chunks.get(0), 0, 7);
  }

  public void testMappingKey() {
    List<FormatStringChunk> chunks = parsePercentFormat("%(language)s");
    assertEquals(1, chunks.size());
    SubstitutionChunk chunk = (SubstitutionChunk)chunks.get(0);
    assertEquals("language", chunk.getMappingKey());
    assertEquals('s', chunk.getConversionType());
  }

  public void testConversionFlags() {
    List<FormatStringChunk> chunks = parsePercentFormat("%#0d");
    assertEquals(1, chunks.size());
    SubstitutionChunk chunk = (SubstitutionChunk)chunks.get(0);
    assertEquals("#0", chunk.getConversionFlags());
  }

  public void testWidth() {
    List<FormatStringChunk> chunks = parsePercentFormat("%345d");
    assertEquals(1, chunks.size());
    SubstitutionChunk chunk = (SubstitutionChunk)chunks.get(0);
    assertEquals("345", chunk.getWidth());
  }

  public void testPrecision() {
    List<FormatStringChunk> chunks = parsePercentFormat("%.2d");
    assertEquals(1, chunks.size());
    SubstitutionChunk chunk = (SubstitutionChunk)chunks.get(0);
    assertEquals("2", chunk.getPrecision());
  }

  public void testLengthModifier() {
    List<FormatStringChunk> chunks = parsePercentFormat("%ld");
    assertEquals(1, chunks.size());
    SubstitutionChunk chunk = (SubstitutionChunk)chunks.get(0);
    assertEquals('l', chunk.getLengthModifier());
  }

  public void testDoubleAsterisk() {
    List<FormatStringChunk> chunks = parsePercentFormat("%**d");
    assertEquals(2, chunks.size());
    SubstitutionChunk chunk = (SubstitutionChunk)chunks.get(0);
    assertEquals(2, chunk.getEndIndex());
    assertEquals('\0', chunk.getConversionType());
  }

  public void testUnclosedMapping() {
    List<FormatStringChunk> chunks = parsePercentFormat("%(name1s");
    SubstitutionChunk chunk = (SubstitutionChunk)chunks.get(0);
    assertEquals("name1s", chunk.getMappingKey());
    assertTrue(chunk.isUnclosedMapping());
  }

  // PY-8372
  public void testNewStyleAutomaticNumbering() {
    final List<SubstitutionChunk> chunks = filterSubstitutions(parseNewStyleFormat("{}, {}"));
    assertEquals(2, chunks.size());
    assertEquals(TextRange.create(0, 2), chunks.get(0).getTextRange());
    assertEquals(TextRange.create(4, 6), chunks.get(1).getTextRange());
  }

  // PY-8372
  public void testNewStylePositionalArgs() {
    final List<SubstitutionChunk> chunks = filterSubstitutions(parseNewStyleFormat("{1}, {0}"));
    assertEquals(2, chunks.size());
    assertEquals(TextRange.create(0, 3), chunks.get(0).getTextRange());
    assertEquals(TextRange.create(5, 8), chunks.get(1).getTextRange());
  }

  // PY-8372
  public void testNewStyleKeywordArgs() {
    final List<SubstitutionChunk> chunks = filterSubstitutions(parseNewStyleFormat("a{foo}{bar}bc"));
    assertEquals(2, chunks.size());
    assertEquals(TextRange.create(1, 6), chunks.get(0).getTextRange());
    assertEquals(TextRange.create(6, 11), chunks.get(1).getTextRange());
  }

  // PY-8372
  public void testBracesEscaping() {
    final List<SubstitutionChunk> chunks = filterSubstitutions(parseNewStyleFormat("\\{\\}, {{}}"));
    assertEquals(1, chunks.size());
    assertEquals(TextRange.create(1, 4), chunks.get(0).getTextRange());
  }

  public void testNewStyleConstant() {
    List<FormatStringChunk> chunks = parseNewStyleFormat("a");
    assertEquals(1, chunks.size());
    assertConstant(chunks.get(0), 0, 1);
  }

  public void testNewStyleUnbalanced() {
    final List<FormatStringChunk> chunks = parseNewStyleFormat("{{{foo}}");
    assertEquals(2, chunks.size());
  }

  public void testNewStyleEscapingAndValue() {
    final List<FormatStringChunk> chunks = parseNewStyleFormat("{{{foo}}}");
    assertEquals(3, chunks.size());
    assertEquals(TextRange.create(0, 2), chunks.get(0).getTextRange());
    assertEquals(TextRange.create(2, 7), chunks.get(1).getTextRange());
    assertEquals(TextRange.create(7, 9), chunks.get(2).getTextRange());
  }

  public void testNewStyleNestedFields() {
    final Field field = doParseAndGetFirstField("u'{foo:{bar} {baz}}'");
    assertEquals("foo", field.getFirstName());
    final List<Field> nestedFields = field.getNestedFields();
    assertSize(2, nestedFields);
    assertEquals("bar", nestedFields.get(0).getFirstName());
    assertEquals("baz", nestedFields.get(1).getFirstName());
  }

  public void testNewStyleTooNestedFields() {
    final ParseResult result = PyNewStyleStringFormatParser.parse("'{:{:{:{}}}} {}'");
    final List<Field> topLevelFields = result.getFields();
    assertSize(2, topLevelFields);
    assertSize(5, result.getAllFields());
    assertSameElements(result.getAllFields().stream().map(f -> f.getDepth()).toArray(), 1, 2, 3, 4, 1);
    assertSameElements(result.getAllFields().stream().map(f -> f.getAutoPosition()).toArray(), 0, 1, 2, 3, 4);
  }

  public void testNewStyleAttrAndLookups() {
    Field field;

    field = doParseAndGetFirstField("u'{foo'");
    assertEquals("foo", field.getFirstName());
    assertEmpty(field.getAttributesAndLookups());

    field = doParseAndGetFirstField("u'{foo}'");
    assertEquals("foo", field.getFirstName());
    assertEmpty(field.getAttributesAndLookups());

    field = doParseAndGetFirstField("u'{foo.bar.baz}'");
    assertEquals("foo", field.getFirstName());
    assertSameElements(field.getAttributesAndLookups(), ".bar", ".baz");

    field = doParseAndGetFirstField("u'{foo[bar][baz]}'");
    assertEquals("foo", field.getFirstName());
    assertSameElements(field.getAttributesAndLookups(), "[bar]", "[baz]");

    field = doParseAndGetFirstField("u'{foo.bar[baz]}'");
    assertEquals("foo", field.getFirstName());
    assertSameElements(field.getAttributesAndLookups(), ".bar", "[baz]");

    field = doParseAndGetFirstField("u'{foo.bar[baz}'");
    assertEquals("foo", field.getFirstName());
    assertSameElements(field.getAttributesAndLookups(), ".bar");

    field = doParseAndGetFirstField("u'{foo[{bar[baz]}'");
    assertEquals("foo", field.getFirstName());
    assertSameElements(field.getAttributesAndLookups(), "[{bar[baz]");

    field = doParseAndGetFirstField("u'{foo[{} {0} {bar.baz}]'");
    assertEquals("foo", field.getFirstName());
    assertSameElements(field.getAttributesAndLookups(), "[{} {0} {bar.baz}]");

    field = doParseAndGetFirstField("u'{foo[bar]baz'");
    assertEquals("foo", field.getFirstName());
    assertSameElements(field.getAttributesAndLookups(), "[bar]");

    field = doParseAndGetFirstField("'{0[foo][.!:][}]}'");
    assertEquals("0", field.getFirstName());
    assertSameElements(field.getAttributesAndLookups(), "[foo]", "[.!:]", "[}]");

    field = doParseAndGetFirstField("'{.foo.bar}'");
    assertEmpty(field.getFirstName());
    assertEquals(TextRange.create(2, 2), field.getFirstNameRange());
    assertSameElements(field.getAttributesAndLookups(), ".foo", ".bar");

    field = doParseAndGetFirstField("'{}'");
    assertEmpty(field.getFirstName());
    assertEquals(TextRange.create(2, 2), field.getFirstNameRange());

    field = doParseAndGetFirstField("'{:foo}'");
    assertEmpty(field.getFirstName());
    assertEquals(TextRange.create(2, 2), field.getFirstNameRange());

    field = doParseAndGetFirstField("'{!r:foo}'");
    assertEmpty(field.getFirstName());
    assertEquals(TextRange.create(2, 2), field.getFirstNameRange());
  }

  public void testNewStyleIncompleteAttrAndLookups() {

    Field field;

    field = doParseAndGetFirstField("'{foo}'");
    assertEquals("foo", field.getFirstName());
    assertEmpty(field.getAttributesAndLookups());

    // recover the first name
    field = doParseAndGetFirstField("'{foo'");
    assertEquals("foo", field.getFirstName());
    assertEmpty(field.getAttributesAndLookups());

    field = doParseAndGetFirstField("'{foo");
    assertEquals("foo", field.getFirstName());
    assertEmpty(field.getAttributesAndLookups());

    field = doParseAndGetFirstField("'{0[foo].bar[baz]}'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar", "[baz]");

    field = doParseAndGetFirstField("'{0[foo].bar[baz]'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar", "[baz]");

    field = doParseAndGetFirstField("'{0[foo].bar[baz]");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar", "[baz]");

    // do not recover unfinished lookups
    field = doParseAndGetFirstField("'{0[foo].bar[ba}'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar");

    field = doParseAndGetFirstField("'{0[foo].bar[ba!}'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar");

    field = doParseAndGetFirstField("'{0[foo].bar[ba:}'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar");

    field = doParseAndGetFirstField("'{0[foo].bar[ba'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar");

    field = doParseAndGetFirstField("'{0[foo].bar[ba");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar");

    // do not recover illegal attributes
    field = doParseAndGetFirstField("'{0[foo].bar[baz]quux}'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar", "[baz]");

    field = doParseAndGetFirstField("'{0[foo].bar[baz]quux!}'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar", "[baz]");

    field = doParseAndGetFirstField("'{0[foo].bar[baz]quux:}'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar", "[baz]");

    field = doParseAndGetFirstField("'{0[foo].bar[baz]quux'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar", "[baz]");

    field = doParseAndGetFirstField("'{0[foo].bar[baz]quux");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar", "[baz]");

    field = doParseAndGetFirstField("'{0..}'");
    assertEquals("0", field.getFirstName());
    assertSameElements(field.getAttributesAndLookups(), ".", ".");

    // recover attributes
    field = doParseAndGetFirstField("'{0[foo].}'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".");

    field = doParseAndGetFirstField("'{0[foo].'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".");

    field = doParseAndGetFirstField("'{0[foo].");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".");

    field = doParseAndGetFirstField("'{0[foo].bar}'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar");

    field = doParseAndGetFirstField("'{0[foo].bar'");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar");

    field = doParseAndGetFirstField("'{0[foo].bar");
    assertSameElements(field.getAttributesAndLookups(), "[foo]", ".bar");
  }

  public void testAutoPosition() {
    final ParseResult result = PyNewStyleStringFormatParser.parse("'{foo} {} {bar} {0} {}'");
    final List<Field> topLevelFields = result.getFields();
    assertSize(5, topLevelFields);

    assertEquals("foo", topLevelFields.get(0).getMappingKey());
    assertNull(topLevelFields.get(0).getPosition());
    assertNull(topLevelFields.get(0).getAutoPosition());

    assertNull(topLevelFields.get(1).getMappingKey());
    assertNull(topLevelFields.get(1).getPosition());
    assertEquals(0, (int)topLevelFields.get(1).getAutoPosition());

    assertEquals("bar", topLevelFields.get(2).getMappingKey());
    assertNull(topLevelFields.get(2).getPosition());
    assertNull(topLevelFields.get(2).getAutoPosition());

    assertNull(topLevelFields.get(3).getMappingKey());
    assertEquals(0, (int)topLevelFields.get(3).getPosition());
    assertNull(topLevelFields.get(3).getAutoPosition());

    assertNull(topLevelFields.get(4).getMappingKey());
    assertNull(topLevelFields.get(4).getPosition());
    assertEquals(1, (int)topLevelFields.get(4).getAutoPosition());
  }

  public void testNewStyleNamedUnicodeEscapes() {
    final List<Field> fields = doParseAndGetTopLevelFields("u\"\\N{LATIN SMALL LETTER B}{:s}\\N{NUMBER SIGN}\\\n" +
                                                           "        {:s}\\N{LATIN SMALL LETTER B}\"");
    assertSize(2, fields);
    assertEquals(":s", fields.get(0).getFormatSpec());
    assertEquals(":s", fields.get(1).getFormatSpec());
  }

  public void testNewStyleNamedUnicodeEscapeInLookup() {
    final Field field = doParseAndGetFirstField("'{foo[\\N{ESCAPE WITH ]}]}'");
    assertSameElements(field.getAttributesAndLookups(), "[\\N{ESCAPE WITH ]}]");
  }

  public void testNewStyleNamedUnicodeEscapeInAttribute() {
    final Field field = doParseAndGetFirstField("'{foo.b\\N{ESCAPE WITH [}.b\\N{ESCAPE WITH .}}'");
    assertSameElements(field.getAttributesAndLookups(), ".b\\N{ESCAPE WITH [}", ".b\\N{ESCAPE WITH .}");
  }

  public void testNewStyleUnclosedLookupEndsWithRightBrace() {
    final Field field = doParseAndGetFirstField("u'{0[}'");
    assertEquals(-1, field.getRightBraceOffset());
    assertEquals(6, field.getFieldEnd());
    assertEmpty(field.getAttributesAndLookups());
  }

  @NotNull
  private static List<Field> doParseAndGetTopLevelFields(@NotNull String nodeText) {
    final ParseResult result = PyNewStyleStringFormatParser.parse(nodeText);
    return result.getFields();
  }

  @NotNull
  private static Field doParseAndGetFirstField(@NotNull String nodeText) {
    final ParseResult result = PyNewStyleStringFormatParser.parse(nodeText);
    final List<Field> topLevelFields = result.getFields();
    assertSize(1, topLevelFields);
    return topLevelFields.get(0);
  }
}
