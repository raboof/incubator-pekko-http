/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2009-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.http.impl.engine.parsing

import java.lang.{ StringBuilder => JStringBuilder }

import org.apache.pekko
import pekko.http.scaladsl.settings.ParserSettings

import scala.annotation.tailrec
import scala.util.Random
import pekko.util.ByteString
import pekko.actor.ActorSystem
import pekko.http.HashCodeCollider
import pekko.http.scaladsl.model.{ ErrorInfo, HttpHeader }
import pekko.http.scaladsl.model.headers._
import pekko.http.impl.model.parser.CharacterClasses
import pekko.http.impl.util._
import pekko.http.scaladsl.settings.ParserSettings.{
  IllegalResponseHeaderNameProcessingMode,
  IllegalResponseHeaderValueProcessingMode
}
import pekko.testkit.EventFilter

abstract class HttpHeaderParserSpec(mode: String, newLine: String) extends PekkoSpecWithMaterializer(
      """
    pekko.http.parsing.max-header-name-length = 60
    pekko.http.parsing.max-header-value-length = 1000
    pekko.http.parsing.header-cache.Host = 300
  """) {
  s"The HttpHeaderParser (mode: $mode)" should {
    "insert the 1st value" in new TestSetup(testSetupMode = TestSetupMode.Unprimed) {
      insert("Hello", "Hello")
      check {
        s"""nodes: 0/H, 0/e, 0/l, 0/l, 0/o, 1/Ω
           |branchData:${" " /* explicit trailing space */}
           |values: Hello""" -> parser.formatRawTrie
      }
      check {
        """-H-e-l-l-o- Hello
          |""" -> parser.formatTrie
      }
    }

    "insert a new branch underneath a simple node" in new TestSetup(testSetupMode = TestSetupMode.Unprimed) {
      insert("Hello", "Hello")
      insert("Hallo", "Hallo")
      check {
        """nodes: 0/H, 1/e, 0/l, 0/l, 0/o, 1/Ω, 0/a, 0/l, 0/l, 0/o, 2/Ω
          |branchData: 6/2/0
          |values: Hello, Hallo""" -> parser.formatRawTrie
      }
      check {
        """   ┌─a-l-l-o- Hallo
          |-H-e-l-l-o- Hello
          |""" -> parser.formatTrie
      }
    }

    "insert a new branch underneath the root" in new TestSetup(testSetupMode = TestSetupMode.Unprimed) {
      insert("Hello", "Hello")
      insert("Hallo", "Hallo")
      insert("Yeah", "Yeah")
      check {
        """nodes: 2/H, 1/e, 0/l, 0/l, 0/o, 1/Ω, 0/a, 0/l, 0/l, 0/o, 2/Ω, 0/Y, 0/e, 0/a, 0/h, 3/Ω
          |branchData: 6/2/0, 0/1/11
          |values: Hello, Hallo, Yeah""" -> parser.formatRawTrie
      }
      check {
        """   ┌─a-l-l-o- Hallo
          |-H-e-l-l-o- Hello
          | └─Y-e-a-h- Yeah
          |""" -> parser.formatTrie
      }
    }

    "insert a new branch underneath an existing branch node" in new TestSetup(testSetupMode = TestSetupMode.Unprimed) {
      insert("Hello", "Hello")
      insert("Hallo", "Hallo")
      insert("Yeah", "Yeah")
      insert("Hoo", "Hoo")
      check {
        """nodes: 2/H, 1/e, 0/l, 0/l, 0/o, 1/Ω, 0/a, 0/l, 0/l, 0/o, 2/Ω, 0/Y, 0/e, 0/a, 0/h, 3/Ω, 0/o, 0/o, 4/Ω
          |branchData: 6/2/16, 0/1/11
          |values: Hello, Hallo, Yeah, Hoo""" -> parser.formatRawTrie
      }
      check {
        """   ┌─a-l-l-o- Hallo
          |-H-e-l-l-o- Hello
          | | └─o-o- Hoo
          | └─Y-e-a-h- Yeah
          |""" -> parser.formatTrie
      }
    }

    "support overriding of previously inserted values" in new TestSetup(testSetupMode = TestSetupMode.Unprimed) {
      insert("Hello", "Hello")
      insert("Hallo", "Hallo")
      insert("Yeah", "Yeah")
      insert("Hoo", "Hoo")
      insert("Hoo", "Foo")
      check {
        """   ┌─a-l-l-o- Hallo
          |-H-e-l-l-o- Hello
          | | └─o-o- Foo
          | └─Y-e-a-h- Yeah
          |""" -> parser.formatTrie
      }
    }

    "retrieve the EmptyHeader" in new TestSetup() {
      parseAndCache(newLine)() shouldEqual EmptyHeader
    }

    "retrieve a cached header with an exact header name match" in new TestSetup() {
      parseAndCache(s"Connection: close${newLine}x")() shouldEqual Connection("close")
    }

    "retrieve a cached header with a case-insensitive header-name match" in new TestSetup() {
      parseAndCache(s"Connection: close${newLine}x")(s"coNNection: close${newLine}x") shouldEqual Connection("close")
    }

    "parse and cache a modelled header" in new TestSetup() {
      parseAndCache(s"Host: spray.io:123${newLine}x")(s"HOST: spray.io:123${newLine}x") shouldEqual Host("spray.io",
        123)
    }

    "parse and cache a Set-Cookie header with a value in double quotes" in new TestSetup() {
      parseAndCache(s"""Set-Cookie: tralala="cookie-value-here"${newLine}x""")() shouldEqual `Set-Cookie`(
        HttpCookie("tralala", "cookie-value-here"))
    }

    "parse and cache an invalid modelled header as RawHeader" in new TestSetup() {
      parseAndCache(s"Content-Type: abc:123${newLine}x")() shouldEqual RawHeader("content-type", "abc:123")
      parseAndCache(s"Origin: localhost:8080${newLine}x")() shouldEqual RawHeader("origin", "localhost:8080")
    }

    "parse and cache an X-Forwarded-For with a hostname in it as a RawHeader" in new TestSetup() {
      parseAndCache(s"X-Forwarded-For: 1.2.3.4, pekko.apache.org${newLine}x")() shouldEqual RawHeader("x-forwarded-for",
        "1.2.3.4, pekko.apache.org")
    }

    "parse and cache an X-Real-Ip with a hostname as it's value as a RawHeader" in new TestSetup() {
      parseAndCache(s"X-Real-Ip: pekko.apache.org${newLine}x")() shouldEqual RawHeader("x-real-ip", "pekko.apache.org")
    }

    "parse and cache a raw header" in new TestSetup(testSetupMode = TestSetupMode.Unprimed) {
      insert("hello: bob", "Hello")
      val (ixA, headerA) = parseLine(s"Fancy-Pants: foo${newLine}x")
      val (ixB, headerB) = parseLine(s"Fancy-pants: foo${newLine}x")
      val newLineWithHyphen = if (newLine == "\r\n") """\r-\n""" else """\n"""
      check {
        s""" ┌─f-a-n-c-y---p-a-n-t-s-:-(Fancy-Pants)- -f-o-o-${newLineWithHyphen}- *Fancy-Pants: foo
           |-h-e-l-l-o-:- -b-o-b- Hello
           |""" -> parser.formatTrie
      }
      ixA shouldEqual ixB
      headerA shouldEqual RawHeader("Fancy-Pants", "foo")
      (headerA should be).theSameInstanceAs(headerB)
    }

    "parse and cache a modelled header with line-folding" in new TestSetup() {
      parseAndCache(s"Connection: foo,${newLine} bar${newLine}x")(
        s"Connection: foo,${newLine} bar${newLine}x") shouldEqual Connection("foo", "bar")
    }

    "parse and cache a header with a tab char in the value" in new TestSetup() {
      parseAndCache(s"Fancy: foo\tbar${newLine}x")() shouldEqual RawHeader("Fancy", "foo bar")
    }

    "parse and cache a header with UTF8 chars in the value" in new TestSetup() {
      parseAndCache(s"2-UTF8-Bytes: árvíztűrő ütvefúrógép${newLine}x")() shouldEqual RawHeader("2-UTF8-Bytes",
        "árvíztűrő ütvefúrógép")
      parseAndCache(s"3-UTF8-Bytes: The € or the $$?${newLine}x")() shouldEqual RawHeader("3-UTF8-Bytes",
        "The € or the $?")
      parseAndCache(s"4-UTF8-Bytes: Surrogate pairs: \uD801\uDC1B\uD801\uDC04\uD801\uDC1B!${newLine}x")() shouldEqual
      RawHeader("4-UTF8-Bytes", "Surrogate pairs: \uD801\uDC1B\uD801\uDC04\uD801\uDC1B!")
    }
    "parse and cache a header with UTF8 chars in the value after an incomplete line" in new TestSetup() {
      a[NotEnoughDataException.type] should be thrownBy parseLineFromBytes(
        ByteString(s"3-UTF8-Bytes: The €").dropRight(1))

      parseAndCache(s"4-UTF8-Bytes: Surrogate pairs: \uD801\uDC1B\uD801\uDC04\uD801\uDC1B!${newLine}x")() shouldEqual
      RawHeader("4-UTF8-Bytes", "Surrogate pairs: \uD801\uDC1B\uD801\uDC04\uD801\uDC1B!")
    }

    "parse multiple header lines subsequently with UTF-8 characters one after another without crashing" in new TestSetup {
      parseLine(s"""Content-Disposition: form-data; name="test"; filename="λ"${newLine}x""")
      // The failing parsing line is one that must share a prefix with the utf-8 line up to the non-ascii char. The next character
      // doesn't even have to be a non-ascii char.
      parseLine(s"""Content-Disposition: form-data; name="test"; filename="test"${newLine}x""")
      // But it could be
      parseLine(s"""Content-Disposition: form-data; name="test"; filename="Б"${newLine}x""")

    }

    "produce an error message for lines with an illegal header name" in new TestSetup() {
      (the[ParsingException] thrownBy parseLine(s" Connection: close${newLine}x") should have).message(
        "Illegal character ' ' in header name")
      (the[ParsingException] thrownBy parseLine(s"Connection : close${newLine}x") should have).message(
        "Illegal character ' ' in header name")
      (the[ParsingException] thrownBy parseLine(s"Connec/tion: close${newLine}x") should have).message(
        "Illegal character '/' in header name")
    }

    "ignore illegal headers when configured to ignore them" in new TestSetup(
      parserSettings = createParserSettings(
        actorSystem = system,
        illegalResponseHeaderNameProcessingMode = IllegalResponseHeaderNameProcessingMode.Ignore)) {
      parseLine(s" Connection: close${newLine}x")
      parseLine(s"Connection : close${newLine}x")
      parseLine(s"Connec/tion: close${newLine}x")
    }

    "ignore illegal headers when configured to ignore and warn about them" in new TestSetup(
      parserSettings = createParserSettings(
        actorSystem = system,
        illegalResponseHeaderNameProcessingMode = IllegalResponseHeaderNameProcessingMode.Warn)) {
      parseLine(s" Connection: close${newLine}x")
      parseLine(s"Connection : close${newLine}x")
      parseLine(s"Connec/tion: close${newLine}x")
    }

    "produce an error message for lines with a too-long header name" in new TestSetup() {
      noException should be thrownBy parseLine(
        s"123456789012345678901234567890123456789012345678901234567890: foo${newLine}x")
      (the[ParsingException] thrownBy parseLine(
        s"1234567890123456789012345678901234567890123456789012345678901: foo${newLine}x") should have).message(
        "HTTP header name exceeds the configured limit of 60 characters")
    }

    "produce an error message for lines with a too-long header value" in new TestSetup() {
      noException should be thrownBy parseLine(s"foo: ${nextRandomString(nextRandomAlphaNumChar _, 1000)}${newLine}x")
      (the[ParsingException] thrownBy parseLine(s"foo: ${nextRandomString(nextRandomAlphaNumChar _, 1001)}${newLine}x") should have).message(
        "HTTP header value exceeds the configured limit of 1000 characters")
    }

    "continue parsing raw headers even if the overall cache value capacity is reached" in new TestSetup() {
      val randomHeaders = Iterator.continually {
        val name = nextRandomString(nextRandomAlphaNumChar _, nextRandomInt(4, 16))
        val value = nextRandomString(() => nextRandomPrintableChar(), nextRandomInt(4, 16))
        RawHeader(name, value)
      }
      randomHeaders.take(300).foldLeft(0) {
        case (acc, rawHeader) => acc + parseAndCache(rawHeader.toString + s"${newLine}x", rawHeader)
      } should be < 300 // number of cache hits is smaller headers successfully parsed
    }

    "continue parsing modelled headers even if the overall cache value capacity is reached" in new TestSetup() {
      val randomHostHeaders = Iterator.continually {
        Host(
          host = nextRandomString(nextRandomAlphaNumChar _, nextRandomInt(4, 8)),
          port = nextRandomInt(1000, 10000))
      }
      randomHostHeaders.take(300).foldLeft(0) {
        case (acc, header) => acc + parseAndCache(header.unsafeToString + s"${newLine}x", header)
      } should be < 300 // number of cache hits is smaller headers successfully parsed
    }

    "continue parsing headers even if the overall cache node capacity is reached" in new TestSetup() {
      val randomHostHeaders = Iterator.continually {
        RawHeader(
          name = nextRandomString(nextRandomAlphaNumChar _, 60),
          value = nextRandomString(nextRandomAlphaNumChar _, 1000))
      }
      randomHostHeaders.take(100).foldLeft(0) {
        case (acc, header) => acc + parseAndCache(header.toString + s"${newLine}x", header)
      } should be < 300 // number of cache hits is smaller headers successfully parsed
    }

    "continue parsing raw headers even if the header-specific cache capacity is reached" in new TestSetup() {
      val randomHeaders = Iterator.continually {
        val value = nextRandomString(() => nextRandomPrintableChar(), nextRandomInt(4, 16))
        RawHeader("Fancy", value)
      }
      randomHeaders.take(20).foldLeft(0) {
        case (acc, rawHeader) => acc + parseAndCache(rawHeader.toString + s"${newLine}x", rawHeader)
      } shouldEqual 12 // configured default per-header cache limit
    }

    "continue parsing modelled headers even if the header-specific cache capacity is reached" in new TestSetup() {
      val randomHeaders = Iterator.continually {
        `User-Agent`(nextRandomString(nextRandomAlphaNumChar _, nextRandomInt(4, 16)))
      }
      randomHeaders.take(40).foldLeft(0) {
        case (acc, header) => acc + parseAndCache(header.toString + s"${newLine}x", header)
      } shouldEqual 12 // configured default per-header cache limit
    }

    "ignore headers whose value cannot be parsed" in new TestSetup(testSetupMode = TestSetupMode.Default) {
      noException should be thrownBy parseLine(s"Server: something; something${newLine}x")
      parseAndCache(s"Server: something; something${newLine}x")() shouldEqual RawHeader("server",
        "something; something")
    }

    "parse most headers to RawHeader when `modeled-header-parsing = off`" in new TestSetup(
      parserSettings = createParserSettings(system).withModeledHeaderParsing(false)) {
      // Connection, Host, and Expect should still be modelled
      parseAndCache(s"Connection: close${newLine}x")(s"CONNECTION: close${newLine}x") shouldEqual Connection("close")
      parseAndCache(s"Host: spray.io:123${newLine}x")(s"HOST: spray.io:123${newLine}x") shouldEqual Host("spray.io",
        123)

      // don't parse other headers
      parseAndCache(s"User-Agent: hmpf${newLine}x")(s"USER-AGENT: hmpf${newLine}x") shouldEqual RawHeader("User-Agent",
        "hmpf")
      parseAndCache(s"X-Forwarded-Host: localhost:8888${newLine}x")(
        s"X-FORWARDED-Host: localhost:8888${newLine}x") shouldEqual RawHeader("X-Forwarded-Host", "localhost:8888")
    }
    "disables the logging of warning message when set the whitelist for illegal headers" in new TestSetup(
      testSetupMode = TestSetupMode.Default,
      parserSettings = createParserSettings(system).withIgnoreIllegalHeaderFor(List("Content-Type"))) {
      // Illegal header is `Retry-After`. So logged warning message
      EventFilter.warning(occurrences = 1).intercept {
        parseLine(s"Retry-After: -10${newLine}x")
      }

      // Illegal header is `Content-Type` and it is in the whitelist. So not logged warning message
      EventFilter.warning(occurrences = 0).intercept {
        parseLine(s"Content-Type: abc:123${newLine}x")
      }
    }
    "not show bad performance characteristics when parameter names' hashCodes collide" in new TestSetup(
      parserSettings = createParserSettings(system).withMaxHeaderValueLength(500 * 1024)) {
      // This is actually quite rare since it needs upping the max header value length
      val numKeys = 10000

      val regularKeys = Iterator.from(1).map(i => s"key_$i").take(numKeys)
      private val zeroHashStrings: Iterator[String] = HashCodeCollider.zeroHashCodeIterator()
      val collidingKeys = zeroHashStrings
        .filter(_.forall(ch => CharacterClasses.tchar(ch)))
        .take(numKeys)

      def createHeader(keys: Iterator[String]): String =
        "Accept: text/plain" + keys.mkString(";", "=x;", "=x") + newLine + "x"

      val regularHeader = createHeader(regularKeys)
      val collidingHeader = createHeader(collidingKeys)
      zeroHashStrings.next().hashCode should be(0)

      def regular(): Unit = {
        val (_, accept: Accept) = parseLine(regularHeader)
        accept.mediaRanges.head.getParams.size should be(numKeys)
      }
      def colliding(): Unit = {
        val (_, accept: Accept) = parseLine(collidingHeader)
        accept.mediaRanges.head.getParams.size should be(numKeys)
      }

      BenchUtils.nanoRace(regular(), colliding()) should be < 3.0 // speed must be in same order of magnitude
    }
  }

  def check(pair: (String, String)) = {
    val (expected, actual) = pair
    actual shouldEqual expected.stripMarginWithNewline("\n")
  }

  sealed trait TestSetupMode
  object TestSetupMode {
    case object Primed extends TestSetupMode
    case object Unprimed extends TestSetupMode
    case object Default extends TestSetupMode // creates a test setup using the default HttpHeaderParser.apply()
  }

  def createParserSettings(
      actorSystem: ActorSystem,
      illegalResponseHeaderNameProcessingMode: IllegalResponseHeaderNameProcessingMode =
        IllegalResponseHeaderNameProcessingMode.Error,
      illegalResponseHeaderValueProcessingMode: IllegalResponseHeaderValueProcessingMode =
        IllegalResponseHeaderValueProcessingMode.Error): ParserSettings =
    ParserSettings(actorSystem)
      .withIllegalResponseHeaderValueProcessingMode(illegalResponseHeaderValueProcessingMode)
      .withIllegalResponseHeaderNameProcessingMode(illegalResponseHeaderNameProcessingMode)

  abstract class TestSetup(testSetupMode: TestSetupMode = TestSetupMode.Primed,
      parserSettings: ParserSettings = createParserSettings(system)) {

    val parser = testSetupMode match {
      case TestSetupMode.Primed =>
        HttpHeaderParser.prime(HttpHeaderParser.unprimed(parserSettings, system.log, defaultIllegalHeaderHandler))
      case TestSetupMode.Unprimed => HttpHeaderParser.unprimed(parserSettings, system.log, defaultIllegalHeaderHandler)
      case TestSetupMode.Default  => HttpHeaderParser(parserSettings, system.log)
    }

    private def defaultIllegalHeaderHandler = (info: ErrorInfo) => system.log.debug(info.formatPretty)

    def insert(line: String, value: AnyRef): Unit =
      if (parser.isEmpty) HttpHeaderParser.insertRemainingCharsAsNewNodes(parser, ByteString(line), value)
      else HttpHeaderParser.insert(parser, ByteString(line), value)

    def parseLineFromBytes(bytes: ByteString) = parser.parseHeaderLine(bytes)() -> {
      system.log.debug(parser.resultHeader.getClass.getSimpleName); parser.resultHeader
    }
    def parseLine(line: String) = parseLineFromBytes(ByteString(line))

    def parseAndCache(lineA: String)(lineB: String = lineA): HttpHeader = {
      val (ixA, headerA) = parseLine(lineA)
      val (ixB, headerB) = parseLine(lineB)
      ixA shouldEqual ixB
      (headerA should be).theSameInstanceAs(headerB)
      headerA
    }

    def parseAndCache(line: String, header: HttpHeader): Int = {
      val (ixA, headerA) = parseLine(line)
      val (ixB, headerB) = parseLine(line)
      headerA shouldEqual header
      headerB shouldEqual header
      ixA shouldEqual ixB
      if (headerA eq headerB) 1 else 0
    }

    private[this] val random = new Random(42)
    def nextRandomPrintableChar(): Char = random.nextPrintableChar()
    def nextRandomInt(min: Int, max: Int) = random.nextInt(max - min) + min
    @tailrec final def nextRandomAlphaNumChar(): Char = {
      val c = nextRandomPrintableChar()
      if (CharacterClasses.ALPHANUM(c)) c else nextRandomAlphaNumChar()
    }
    @tailrec final def nextRandomString(
        charGen: () => Char, len: Int, sb: JStringBuilder = new JStringBuilder): String =
      if (sb.length < len) nextRandomString(charGen, len, sb.append(charGen())) else sb.toString
  }
}

class HttpHeaderParserCRLFSpec extends HttpHeaderParserSpec("CRLF", "\r\n")

class HttpHeaderParserLFSpec extends HttpHeaderParserSpec("LF", "\n")
