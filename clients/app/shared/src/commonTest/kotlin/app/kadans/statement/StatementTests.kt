package app.kadans.statement

import app.kadans.statement.StatementText.DateOrder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate

/** A bank's file read on the device (ARCHITECTURE → "Statements in, data out"): its cells, its columns, its lines. */
class StatementTests {
    private fun cents(cell: String) = StatementText.cents(cell)

    @Test
    fun an_amount_is_read_as_banks_write_them() {
        assertEquals(125050, cents("1,250.50"))
        assertEquals(125050, cents("1.250,50"))
        assertEquals(125050, cents("1 250,50"))
        assertEquals(125050, cents("1 250,50")) // the space that does not break, as French banks print it
        assertEquals(125050, cents("HTG 1,250.50"))
        assertEquals(1200, cents("$12.00"))
        assertEquals(1200, cents("12,00 €"))
        assertEquals(1250, cents("12,5"))
        assertEquals(1200, cents("12"))
        assertEquals(50, cents(".50"))
        // A lone sign followed by three digits separates thousands; several of them always do.
        assertEquals(125000, cents("1,250"))
        assertEquals(125000000, cents("1.250.000"))
        // More decimals than a movement holds: rounded as a bank would print it.
        assertEquals(1235, cents("12.3456"))
    }

    @Test
    fun a_negative_amount_is_told_by_its_minus_its_brackets_or_its_letters() {
        assertEquals(-1250, cents("-12.50"))
        assertEquals(-1250, cents("12.50-"))
        assertEquals(-1250, cents("−12,50")) // the typographic minus
        assertEquals(-1250, cents("$-12.50"))
        assertEquals(-1250, cents("(12.50)"))
        assertEquals(-1250, cents("12.50 DR"))
        assertEquals(1250, cents("12.50 CR"))
        assertEquals(1250, cents("+12.50"))
    }

    @Test
    fun what_is_not_an_amount_is_not_read_as_one() {
        assertNull(cents(""))
        assertNull(cents("Groceries"))
        assertNull(cents("2026-11-16"))
        assertNull(cents("16/11/2026"))
        assertNull(cents("14:30"))
        assertNull(cents("12 Nov 2026"))
        assertNull(cents("REF 12-34"))
        assertNull(cents("99999999999999.00")) // more than any account holds
        assertEquals(StatementText.MAX_CENTS, cents("1,000,000,000,000.00")) // the most a movement holds…
        assertNull(cents("1,000,000,000,000.01")) // …and nothing above it
    }

    @Test
    fun a_date_is_read_in_the_order_its_file_uses() {
        val day = LocalDate(2026, 11, 3)
        assertEquals(day, StatementText.date("03/11/2026", DateOrder.DayMonthYear))
        assertEquals(day, StatementText.date("11/03/2026", DateOrder.MonthDayYear))
        assertEquals(day, StatementText.date("3.11.26", DateOrder.DayMonthYear))
        assertEquals(day, StatementText.date("03-11-2026 14:32:07", DateOrder.DayMonthYear))
        // With the year in front there is only one way to read it, whatever was guessed.
        assertEquals(day, StatementText.date("2026-11-03", DateOrder.MonthDayYear))
        assertEquals(day, StatementText.date("2026/11/03", DateOrder.DayMonthYear))
        assertEquals(day, StatementText.date("2026-11-03T09:15:00Z", DateOrder.DayMonthYear))
        // In letters, English or French.
        assertEquals(day, StatementText.date("03-Nov-2026", DateOrder.MonthDayYear))
        assertEquals(day, StatementText.date("Nov 3, 2026", DateOrder.DayMonthYear))
        assertEquals(day, StatementText.date("3 novembre 2026", DateOrder.DayMonthYear))
        assertEquals(LocalDate(2026, 6, 3), StatementText.date("3 juin 2026", DateOrder.DayMonthYear))
        assertEquals(LocalDate(2026, 7, 3), StatementText.date("3 juil. 2026", DateOrder.DayMonthYear))
        assertEquals(LocalDate(2026, 2, 3), StatementText.date("3 févr. 2026", DateOrder.DayMonthYear))
        // Not a date, or not a day that exists.
        assertNull(StatementText.date("31/02/2026", DateOrder.DayMonthYear))
        assertNull(StatementText.date("13/13/2026", DateOrder.DayMonthYear))
        assertNull(StatementText.date("Total", DateOrder.DayMonthYear))
        assertNull(StatementText.date("1250.50", DateOrder.DayMonthYear))
    }

    @Test
    fun the_order_of_a_dates_numbers_is_told_by_the_dates_themselves_when_they_can() {
        assertEquals(DateOrder.DayMonthYear, StatementText.dateOrder(listOf("03/11/2026", "16/11/2026")))
        assertEquals(DateOrder.MonthDayYear, StatementText.dateOrder(listOf("11/03/2026", "11/16/2026")))
        assertEquals(DateOrder.YearMonthDay, StatementText.dateOrder(listOf("2026-11-03")))
        // Every day before the 13th: nothing tells, the person is asked.
        assertNull(StatementText.dateOrder(listOf("03/11/2026", "05/11/2026")))
        // Unless one way of reading them puts a line in the future: a statement holds what happened.
        val today = LocalDate(2026, 11, 16)
        assertEquals(DateOrder.DayMonthYear, StatementText.dateOrder(listOf("03/11/2026", "12/11/2026"), today)) // not 11 December
        assertEquals(DateOrder.MonthDayYear, StatementText.dateOrder(listOf("03/12/2026", "11/05/2026"), today)) // not 3 December
        assertNull(StatementText.dateOrder(listOf("03/04/2026", "05/06/2026"), today)) // both ways in the past
        assertNull(StatementText.dateOrder(listOf("3 novembre 2026"), today)) // in letters: no order to tell
    }

    @Test
    fun a_french_bank_export_with_two_columns_of_money_and_lines_around_the_table() {
        // Semicolons, decimal commas, a few lines about the account first and a total last, in the old Windows encoding.
        val file = """
            Relevé de compte;Compte courant
            Titulaire;Jean Baptiste

            Date;Libellé;Débit;Crédit;Solde
            03/11/2026;PAIEMENT CARTE MARCHÉ DE FER;1 250,00;;48 750,00
            05/11/2026;VIREMENT REÇU SALAIRE;;85 000,00;133 750,00
            16/11/2026;"RETRAIT GAB; RUE CAPOIS";5 000,00;;128 750,00
            Total;;6 250,00;85 000,00;
        """.trimIndent()
        val table = CsvFile.table(StatementText.decode(windows1252(file)))

        assertEquals(listOf("Date", "Libellé", "Débit", "Crédit", "Solde"), table.header)
        assertEquals(4, table.rows.size) // the three movements and the total, which is read as no movement below
        val mapping = CsvFile.guess(table)
        assertEquals(CsvMapping(date = 0, order = DateOrder.DayMonthYear, text = 1, moneyOut = 2, moneyIn = 3), mapping)

        val read = CsvFile.read(table, mapping)
        assertEquals(
            listOf(
                StatementLine(LocalDate(2026, 11, 3), -125000, "PAIEMENT CARTE MARCHÉ DE FER"),
                StatementLine(LocalDate(2026, 11, 5), 8500000, "VIREMENT REÇU SALAIRE"),
                StatementLine(LocalDate(2026, 11, 16), -500000, "RETRAIT GAB; RUE CAPOIS"),
            ),
            read.lines,
        )
        assertEquals(3, read.unread) // the two lines about the account, and the total (no date)
    }

    @Test
    fun an_american_bank_export_with_one_signed_amount() {
        val file = "Posting Date,Description,Amount,Balance\r\n" +
            "11/03/2026,\"POS PURCHASE, GIANT MARKET #12\",-42.17,1957.83\r\n" +
            "11/16/2026,PAYROLL DEPOSIT,1500.00,3457.83\r\n" +
            "11/16/2026,\"He said \"\"thanks\"\"\",-5.00,3452.83\r\n"
        val table = CsvFile.table(file)
        val mapping = CsvFile.guess(table)

        assertEquals(CsvMapping(date = 0, order = DateOrder.MonthDayYear, text = 1, amount = 2), mapping)
        assertEquals(
            listOf(
                StatementLine(LocalDate(2026, 11, 3), -4217, "POS PURCHASE, GIANT MARKET #12"),
                StatementLine(LocalDate(2026, 11, 16), 150000, "PAYROLL DEPOSIT"),
                StatementLine(LocalDate(2026, 11, 16), -500, "He said \"thanks\""),
            ),
            CsvFile.read(table, mapping).lines,
        )
    }

    @Test
    fun a_file_without_a_header_is_read_by_what_is_in_its_columns() {
        val file = "2026-11-03\tMARCHE EN FER\t-1250.00\n2026-11-05\tSALAIRE\t85000.00\n"
        val table = CsvFile.table(file)

        assertNull(table.header)
        val mapping = CsvFile.guess(table)
        assertEquals(CsvMapping(date = 0, order = DateOrder.YearMonthDay, text = 1, amount = 2), mapping)
        assertEquals(listOf(-125000L, 8500000L), CsvFile.read(table, mapping).lines.map { it.cents })
    }

    @Test
    fun a_columns_name_is_believed_only_when_its_cells_agree() {
        // "Date valeur" holds dates: it is neither the amount ("valeur") nor the day the movement was made.
        val french = CsvFile.table(
            "Date valeur;Date;Référence;Libellé;Montant;Solde\n" +
                "04/11/2026;03/11/2026;000123;ACHAT MARCHÉ;-1 250,00;48 750,00\n" +
                "17/11/2026;16/11/2026;000124;SALAIRE;85 000,00;133 750,00\n",
        )
        val mapping = CsvFile.guess(french)
        assertEquals(1, mapping.date) // the movement's own day, though the value date stands first
        assertEquals(4, mapping.amount)
        assertEquals(3, mapping.text) // the description, not the reference before it
        assertNull(mapping.moneyOut)
        assertEquals(listOf(LocalDate(2026, 11, 3), LocalDate(2026, 11, 16)), CsvFile.read(french, mapping).lines.map { it.date })
        assertEquals(listOf(-125000L, 8500000L), CsvFile.read(french, mapping).lines.map { it.cents })

        // A column called "Total" that holds words is not the amount; the one that holds amounts is.
        val odd = CsvFile.table("Date,Total,Details,Paid\n03/11/2026,weekly,COFFEE,-3.50\n16/11/2026,weekly,TEA,-2.00\n")
        assertEquals(3, CsvFile.guess(odd).amount)
        assertEquals(2, CsvFile.guess(odd).text)
    }

    @Test
    fun headers_in_kreyol_are_read_too() {
        val table = CsvFile.table("Dat;Deskripsyon;Debi;Kredi;Balans\n03/11/2026;MACHE AN FÈ;1 250,00;;48 750,00\n16/11/2026;SALÈ;;85 000,00;133 750,00\n")
        assertEquals(CsvMapping(date = 0, order = DateOrder.DayMonthYear, text = 1, moneyOut = 2, moneyIn = 3), CsvFile.guess(table))
    }

    @Test
    fun without_names_to_go_by_the_cells_say_what_a_column_is() {
        // No header: a date, a reference, a text, money out, money in, a balance.
        val bare = CsvFile.table(
            "03/11/2026;000123;MARCHE EN FER;1 250,00;;48 750,00\n" +
                "05/11/2026;000124;SALAIRE;;85 000,00;133 750,00\n" +
                "16/11/2026;000125;RETRAIT GAB;5 000,00;;128 750,00\n",
        )
        assertNull(bare.header)
        val mapping = CsvFile.guess(bare)
        // Each row fills one of the two columns: money out, then money in. The balance, filled on every row, is neither.
        assertEquals(CsvMapping(date = 0, order = DateOrder.DayMonthYear, text = 2, moneyOut = 3, moneyIn = 4), mapping)
        assertEquals(listOf(-125000L, 8500000L, -500000L), CsvFile.read(bare, mapping).lines.map { it.cents })

        // One signed amount after a column of references: whole numbers are not taken for money when another column is.
        val signed = CsvFile.table("2026-11-03,000123,COFFEE,-3.50,96.50\n2026-11-16,000124,PAY,1500.00,1596.50\n")
        assertEquals(CsvMapping(date = 0, order = DateOrder.YearMonthDay, text = 2, amount = 3), CsvFile.guess(signed))

        // Headers in words not known here: the debit's partner is still found, by how the rows are shared.
        val foreign = CsvFile.table("Datum;Text;Debit;Haben\n03.11.2026;MARKT;12,50;\n16.11.2026;LOHN;;1500,00\n")
        val guessed = CsvFile.guess(foreign)
        assertEquals(2 to 3, guessed.moneyOut to guessed.moneyIn)
        assertEquals(0, guessed.date)
    }

    @Test
    fun a_short_statement_under_a_long_heading_is_still_found() {
        // Six lines about the account, each of two cells, and only two movements: the table is where the movements are.
        val file = """
            Banque;Agence centrale
            Client;Client de démonstration
            Compte;Compte courant
            Devise;HTG
            Période;01/11/2026 au 30/11/2026
            Solde initial;50 000,00

            Date;Libellé;Débit;Crédit
            03/11/2026;MARCHE EN FER;1 250,00;
            16/11/2026;SALAIRE;;85 000,00
        """.trimIndent()
        val table = CsvFile.table(file)
        assertEquals(listOf("Date", "Libellé", "Débit", "Crédit"), table.header)
        assertEquals(2, table.rows.size)
        assertEquals(6, table.unread)
        assertEquals(listOf(-125000L, 8500000L), CsvFile.read(table, CsvFile.guess(table)).lines.map { it.cents })
    }

    @Test
    fun a_card_statement_counts_its_purchases_above_zero_and_the_person_says_so() {
        val table = CsvFile.table("Date,Details,Amount\n03/11/2026,SUPERMARKET,42.17\n16/11/2026,PAYMENT RECEIVED,-500.00\n")
        val mapping = CsvFile.guess(table).copy(order = DateOrder.DayMonthYear, positiveIsIncome = false)

        assertEquals(listOf(-4217L, 50000L), CsvFile.read(table, mapping).lines.map { it.cents })
    }

    @Test
    fun dates_that_do_not_say_their_order_take_the_one_the_person_is_used_to_until_corrected() {
        val table = CsvFile.table("Date,Details,Amount\n03/11/2026,A,-1.00\n05/11/2026,B,-2.00\n")

        assertEquals(DateOrder.DayMonthYear, CsvFile.guess(table, fallbackOrder = DateOrder.DayMonthYear).order)
        assertEquals(DateOrder.MonthDayYear, CsvFile.guess(table, fallbackOrder = DateOrder.MonthDayYear).order)
        assertEquals(LocalDate(2026, 3, 11), CsvFile.read(table, CsvFile.guess(table, DateOrder.MonthDayYear)).lines.first().date)
    }

    @Test
    fun rows_that_are_no_movement_are_counted_not_invented() {
        val table = CsvFile.table("Date,Details,Amount\n03/11/2026,COFFEE,-3.50\n04/11/2026,PENDING,\n05/11/2026,NOTHING,0.00\nnot a date,X,-1.00\n")
        val read = CsvFile.read(table, CsvFile.guess(table))

        assertEquals(1, read.lines.size)
        assertEquals(3, read.unread)
        // Without a column for the date, nothing is read at all.
        assertEquals(0, CsvFile.read(table, CsvMapping(amount = 2)).lines.size)
    }

    @Test
    fun a_file_longer_than_what_is_read_at_once_says_so() {
        fun rows(count: Int) = "Date,Details,Amount\n" + (1..count).joinToString("\n") { "2026-10-${(it % 28 + 1).toString().padStart(2, '0')},SHOP $it,-1.00" }
        // Exactly what is read at once (the header is one of the rows): whole.
        val whole = CsvFile.table(rows(CsvFile.MAX_ROWS - 1))
        assertEquals(false, whole.truncated)
        assertEquals(CsvFile.MAX_ROWS - 1, CsvFile.read(whole, CsvFile.guess(whole)).lines.size)
        // More: the first ones are read, and the person is told the rest was left out.
        val long = CsvFile.table(rows(CsvFile.MAX_ROWS + 500))
        val read = CsvFile.read(long, CsvFile.guess(long))
        assertTrue(read.truncated)
        assertEquals(CsvFile.MAX_ROWS - 1, read.lines.size)

        fun ofx(count: Int) = "<OFX><BANKTRANLIST>" + (1..count).joinToString("") { "<STMTTRN><DTPOSTED>20261103<TRNAMT>-1.00<FITID>20261103-$it<NAME>SHOP\n" } + "</BANKTRANLIST></OFX>"
        assertEquals(false, Ofx.read(ofx(CsvFile.MAX_ROWS)).truncated)
        assertTrue(Ofx.read(ofx(CsvFile.MAX_ROWS + 1)).truncated)
        assertEquals(CsvFile.MAX_ROWS, Ofx.read(ofx(CsvFile.MAX_ROWS + 1)).lines.size)
    }

    @Test
    fun a_lines_text_becomes_a_note_that_is_only_text() {
        assertEquals("POS 4521 MARKET", Statements.note("  POS   4521\tMARKET\u0007 "))
        assertEquals(1000, Statements.note("x".repeat(5000)).length)
        assertEquals("", Statements.note("   "))
    }

    @Test
    fun an_ofx_file_is_read_in_its_old_form_and_its_new_one() {
        val sgml = """
            OFXHEADER:100
            DATA:OFXSGML
            <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>USD
            <BANKACCTFROM><BANKID>123456<ACCTID>0000987654<ACCTTYPE>CHECKING</BANKACCTFROM>
            <BANKTRANLIST>
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20261103120000[-5:EST]<TRNAMT>-42.17<FITID>2026110300001<NAME>GIANT MARKET #12<MEMO>POS PURCHASE
            <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20261116<TRNAMT>1500.00<FITID>2026111600002<NAME>PAYROLL &amp; BONUS
            <STMTTRN><TRNTYPE>OTHER<DTPOSTED>20261116<TRNAMT>0.00<FITID>2026111600003<NAME>NOTHING
            </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
        """.trimIndent()
        val expected = listOf(
            StatementLine(LocalDate(2026, 11, 3), -4217, "GIANT MARKET #12 – POS PURCHASE", "2026110300001"),
            StatementLine(LocalDate(2026, 11, 16), 150000, "PAYROLL & BONUS", "2026111600002"),
        )
        assertEquals(StatementRead(expected, unread = 1, currency = "USD"), Ofx.read(sgml))

        val xml = """<?xml version="1.0"?><?OFX OFXHEADER="200"?><OFX><BANKTRANLIST>
            <STMTTRN><TRNTYPE>DEBIT</TRNTYPE><DTPOSTED>20261103120000</DTPOSTED><TRNAMT>-42,17</TRNAMT><FITID>2026110300001</FITID><NAME>GIANT MARKET #12</NAME><MEMO>POS PURCHASE</MEMO></STMTTRN>
            <STMTTRN><TRNTYPE>CREDIT</TRNTYPE><DTPOSTED>20261116</DTPOSTED><TRNAMT>1500.00</TRNAMT><FITID>2026111600002</FITID><NAME>PAYROLL &amp; BONUS</NAME></STMTTRN>
            </BANKTRANLIST></OFX>"""
        assertEquals(expected, Ofx.read(xml).lines)
    }

    /** A Dutch bank's camt.053 as its online banking exports it (version 02, no prefix), one account. */
    private val camt02 = """<?xml version="1.0" encoding="UTF-8"?>
        <Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">
          <BkToCstmrStmt>
            <GrpHdr><MsgId>20261116-1</MsgId><CreDtTm>2026-11-16T08:00:00</CreDtTm></GrpHdr>
            <Stmt>
              <Id>2026-11</Id>
              <Acct><Id><IBAN>NL00BANK0123456789</IBAN></Id><Ccy>EUR</Ccy></Acct>
              <Bal><Tp><CdOrPrtry><Cd>OPBD</Cd></CdOrPrtry></Tp><Amt Ccy="EUR">1000.00</Amt><CdtDbtInd>CRDT</CdtDbtInd><Dt><Dt>2026-11-01</Dt></Dt></Bal>
              <Ntry>
                <Amt Ccy="EUR">42.17</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
                <BookgDt><Dt>2026-11-03</Dt></BookgDt><ValDt><Dt>2026-11-04</Dt></ValDt>
                <AcctSvcrRef>2026110300001</AcctSvcrRef>
                <BkTxCd><Prtry><Cd>BA</Cd></Prtry></BkTxCd>
                <NtryDtls><TxDtls>
                  <Refs><EndToEndId>NOTPROVIDED</EndToEndId></Refs>
                  <AmtDtls><TxAmt><Amt Ccy="EUR">42.17</Amt></TxAmt></AmtDtls>
                  <RltdPties><Dbtr><Nm>Client de démonstration</Nm></Dbtr><Cdtr><Nm>Albert &amp; Zonen B.V.</Nm></Cdtr><CdtrAcct><Id><IBAN>NL00BANK0000000001</IBAN></Id></CdtrAcct></RltdPties>
                  <RmtInf><Ustrd>Pasvolgnr 001</Ustrd><Ustrd>03-11-2026 12:14</Ustrd></RmtInf>
                </TxDtls></NtryDtls>
                <AddtlNtryInf>Betaalautomaat</AddtlNtryInf>
              </Ntry>
              <Ntry>
                <Amt Ccy="EUR">1500</Amt><CdtDbtInd>CRDT</CdtDbtInd><Sts>BOOK</Sts>
                <BookgDt><DtTm>2026-11-13T09:30:00+01:00</DtTm></BookgDt>
                <AcctSvcrRef>2026111300002</AcctSvcrRef>
                <NtryDtls><TxDtls>
                  <RltdPties><Dbtr><Nm>Employeur S.A.</Nm></Dbtr><Cdtr><Nm>Client de démonstration</Nm></Cdtr></RltdPties>
                  <RmtInf><Ustrd><![CDATA[Salaire 11/2026 <net>]]></Ustrd></RmtInf>
                </TxDtls></NtryDtls>
              </Ntry>
              <Ntry>
                <Amt Ccy="EUR">1.250</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
                <BookgDt><Dt>2026-11-14</Dt></BookgDt>
                <AddtlNtryInf>Frais de tenue de compte &#233;t&#xE9;</AddtlNtryInf>
              </Ntry>
              <Ntry>
                <Amt Ccy="EUR">9.99</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>PDNG</Sts>
                <BookgDt><Dt>2026-11-15</Dt></BookgDt><AddtlNtryInf>En attente</AddtlNtryInf>
              </Ntry>
              <Ntry>
                <Amt Ccy="EUR">300.00</Amt><CdtDbtInd>DBIT</CdtDbtInd><Sts>BOOK</Sts>
                <BookgDt><Dt>2026-11-16</Dt></BookgDt>
                <NtryDtls><Btch><NbOfTxs>2</NbOfTxs></Btch>
                  <TxDtls><Refs><AcctSvcrRef>A1</AcctSvcrRef></Refs><AmtDtls><TxAmt><Amt Ccy="EUR">100.00</Amt></TxAmt></AmtDtls><RltdPties><Cdtr><Nm>Loyer</Nm></Cdtr></RltdPties></TxDtls>
                  <TxDtls><Refs><AcctSvcrRef>A2</AcctSvcrRef></Refs><AmtDtls><TxAmt><Amt Ccy="EUR">200.00</Amt></TxAmt></AmtDtls><RltdPties><Cdtr><Nm>Assurance</Nm></Cdtr></RltdPties></TxDtls>
                </NtryDtls>
                <AddtlNtryInf>Paiements groupés (2)</AddtlNtryInf>
              </Ntry>
            </Stmt>
          </BkToCstmrStmt>
        </Document>"""

    @Test
    fun a_camt_statement_is_read_without_asking_anything() {
        assertEquals(StatementKind.Camt, Statements.kind(camt02.encodeToByteArray()))
        val read = Camt.read(camt02)

        assertEquals(
            listOf(
                // Paid to a shop: its name and what the payment said; the bank's own reference names the line.
                StatementLine(LocalDate(2026, 11, 3), -4217, "Albert & Zonen B.V. – Pasvolgnr 001 03-11-2026 12:14", "2026110300001"),
                // Money in: who paid. A moment is read as its day; a value written as it is (CDATA) is plain text.
                StatementLine(LocalDate(2026, 11, 13), 150000, "Employeur S.A. – Salaire 11/2026 <net>", "2026111300002"),
                // "1.250" in a standard's file is one and a quarter, never 1,250; signs written by their numbers.
                StatementLine(LocalDate(2026, 11, 14), -125, "Frais de tenue de compte été"),
                // Several transactions gathered by the bank are one line of the account, with the entry's own amount.
                StatementLine(LocalDate(2026, 11, 16), -30000, "Paiements groupés (2)"),
            ),
            read.lines,
        )
        assertEquals(1, read.unread) // the pending one: not on the account yet
        assertEquals("EUR", read.currency)
        assertEquals(false, read.otherAccounts)
        assertEquals(false, read.truncated)
        // The opening balance is not a movement, and nothing of the details' own amounts was taken for the entry's.
        assertEquals(-4217L + 150000 - 125 - 30000, read.lines.sumOf { it.cents })
    }

    @Test
    fun a_newer_camt_file_with_prefixes_and_two_accounts_gives_the_first_accounts_lines() {
        // Version 08: a prefix on every tag, the status in a code, the party one level deeper; then another account.
        val file = """<?xml version="1.0" encoding="UTF-8"?>
            <ns2:Document xmlns:ns2="urn:iso:std:iso:20022:tech:xsd:camt.053.001.08">
              <ns2:BkToCstmrStmt>
                <ns2:Stmt>
                  <ns2:Acct><ns2:Id><ns2:Othr><ns2:Id>0000987654</ns2:Id></ns2:Othr></ns2:Id><ns2:Ccy>USD</ns2:Ccy></ns2:Acct>
                  <ns2:Ntry>
                    <ns2:Amt Ccy="USD">18.99</ns2:Amt><ns2:CdtDbtInd>DBIT</ns2:CdtDbtInd><ns2:Sts><ns2:Cd>BOOK</ns2:Cd></ns2:Sts>
                    <ns2:BookgDt><ns2:Dt>2026-11-09</ns2:Dt></ns2:BookgDt><ns2:AcctSvcrRef>NOTPROVIDED</ns2:AcctSvcrRef>
                    <ns2:NtryDtls><ns2:TxDtls><ns2:RltdPties><ns2:Cdtr><ns2:Pty><ns2:Nm>ONLINE SHOP</ns2:Nm></ns2:Pty></ns2:Cdtr></ns2:RltdPties></ns2:TxDtls></ns2:NtryDtls>
                  </ns2:Ntry>
                  <ns2:Ntry>
                    <ns2:Amt Ccy="USD">5.00</ns2:Amt><ns2:CdtDbtInd>DBIT</ns2:CdtDbtInd><ns2:Sts><ns2:Cd>FUTR</ns2:Cd></ns2:Sts>
                    <ns2:BookgDt><ns2:Dt>2026-11-20</ns2:Dt></ns2:BookgDt>
                  </ns2:Ntry>
                </ns2:Stmt>
                <ns2:Stmt>
                  <ns2:Acct><ns2:Id><ns2:Othr><ns2:Id>0000111111</ns2:Id></ns2:Othr></ns2:Id><ns2:Ccy>USD</ns2:Ccy></ns2:Acct>
                  <ns2:Ntry>
                    <ns2:Amt Ccy="USD">777.00</ns2:Amt><ns2:CdtDbtInd>CRDT</ns2:CdtDbtInd><ns2:Sts><ns2:Cd>BOOK</ns2:Cd></ns2:Sts>
                    <ns2:BookgDt><ns2:Dt>2026-11-10</ns2:Dt></ns2:BookgDt><ns2:AddtlNtryInf>SAVINGS</ns2:AddtlNtryInf>
                  </ns2:Ntry>
                </ns2:Stmt>
              </ns2:BkToCstmrStmt>
            </ns2:Document>"""
        assertEquals(StatementKind.Camt, Statements.kind(file.encodeToByteArray()))
        val read = Camt.read(file)

        // A reference that says there is none is not one: the line is named by what it holds.
        assertEquals(listOf(StatementLine(LocalDate(2026, 11, 9), -1899, "ONLINE SHOP")), read.lines)
        assertEquals(1, read.unread) // the one yet to come
        assertEquals("USD", read.currency)
        assertTrue(read.otherAccounts) // the savings account's line belongs on another account
    }

    @Test
    fun a_reference_two_lines_share_names_neither_and_no_two_lines_get_one_key() {
        val shared = listOf(
            StatementLine(LocalDate(2026, 11, 3), -350, "COFFEE", "REF-0001"),
            StatementLine(LocalDate(2026, 11, 3), -1250, "MARKET", "REF-0001"), // the bank repeated it
            StatementLine(LocalDate(2026, 11, 4), -900, "TAXI", "REF-0002"),
            StatementLine(LocalDate(2026, 11, 5), -100, "FEE", "0000000"), // zeros on every line of such a bank
        )
        val keys = ImportKeys.of(shared)
        assertEquals(4, keys.toSet().size)
        assertEquals(listOf("l1", "l1", "b1", "l1"), keys.map { it.take(2) })
        // Named by what they hold, as if the bank had given no reference at all.
        assertEquals(ImportKeys.of(shared.map { it.copy(bankId = null) }).take(2), keys.take(2))
        // An id that looks like a rank in the statement (the next statement starts at 1 again) is not believed either.
        val ranked = listOf(StatementLine(LocalDate(2026, 11, 3), -350, "COFFEE", "1"), StatementLine(LocalDate(2026, 11, 4), -900, "TAXI", "2"))
        assertEquals(listOf("l1", "l1"), ImportKeys.of(ranked).map { it.take(2) })
        // A file of lines all alike, each with the same reference: as many keys as lines.
        val alike = List(5) { StatementLine(LocalDate(2026, 11, 3), -350, "COFFEE", "NOTPROVIDED") }
        assertEquals(5, ImportKeys.of(alike).toSet().size)
    }

    @Test
    fun a_standards_amount_has_no_sign_between_thousands() {
        assertEquals(125L, StatementText.decimalCents("1.250"))
        assertEquals(-4217L, StatementText.decimalCents("-42.17"))
        assertEquals(-4217L, StatementText.decimalCents("-42,17")) // OFX allows the comma
        assertEquals(150000L, StatementText.decimalCents("1500"))
        assertEquals(50L, StatementText.decimalCents(".5"))
        assertEquals(1235L, StatementText.decimalCents("+12.3456"))
        assertNull(StatementText.decimalCents("1,500.00")) // not as the standard writes it: left to the tolerant reader
        assertNull(StatementText.decimalCents("12 EUR"))
        assertNull(StatementText.decimalCents(""))
        assertNull(StatementText.decimalCents("-"))
        // In an OFX file too; one whose bank writes thousands with a sign is still read.
        fun ofx(amount: String) = Ofx.read("<OFX><BANKTRANLIST><STMTTRN><DTPOSTED>20261103<TRNAMT>$amount<FITID>F100<NAME>X\n</BANKTRANLIST></OFX>").lines.single().cents
        assertEquals(-125L, ofx("-1.250"))
        assertEquals(-150000L, ofx("-1,500.00"))
    }

    @Test
    fun the_kind_of_a_file_is_told_by_what_is_in_it() {
        assertEquals(StatementKind.Csv, Statements.kind("Date,Details,Amount\n03/11/2026,A,-1.00\n".encodeToByteArray()))
        assertEquals(StatementKind.Ofx, Statements.kind("OFXHEADER:100\nDATA:OFXSGML\n<OFX>".encodeToByteArray()))
        assertEquals(StatementKind.Pdf, Statements.kind("%PDF-1.7 ...".encodeToByteArray()))
        assertEquals(StatementKind.Spreadsheet, Statements.kind(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)))
        // OFX in its XML form is still OFX, and a camt statement is one; other XML and a saved web page are not read as tables.
        assertEquals(StatementKind.Ofx, Statements.kind("""<?xml version="1.0"?><?OFX OFXHEADER="200"?><OFX>""".encodeToByteArray()))
        assertEquals(StatementKind.Camt, Statements.kind("""<?xml version="1.0"?><Document xmlns="urn:iso:std:iso:20022:tech:xsd:camt.053.001.02">""".encodeToByteArray()))
        assertEquals(StatementKind.Unreadable, Statements.kind("""<?xml version="1.0"?><Invoice><Total>12.00</Total><Note>Doe, John</Note></Invoice>""".encodeToByteArray()))
        assertEquals(StatementKind.Unreadable, Statements.kind("<html><body><table><tr><td>03/11/2026</td><td>-1,00</td></tr></table></body></html>".encodeToByteArray()))
        assertEquals(StatementKind.Unreadable, Statements.kind(byteArrayOf()))
        assertEquals(StatementKind.Unreadable, Statements.kind(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x00, 0x00))) // a picture
        assertEquals(StatementKind.Unreadable, Statements.kind(ByteArray(StatementText.MAX_BYTES + 1) { 'a'.code.toByte() }))
    }

    @Test
    fun a_file_is_read_in_the_encoding_it_was_written_in() {
        assertEquals("Débit €", StatementText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "Débit €".encodeToByteArray()))
        assertEquals("Débit €", StatementText.decode(windows1252("Débit €")))
        // Excel's "Unicode text": two bytes a letter, the low one first.
        assertEquals("Dé", StatementText.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x44, 0x00, 0xE9.toByte(), 0x00)))
    }

    @Test
    fun the_same_line_gets_the_same_key_every_time_and_two_lines_alike_get_two() {
        val coffee = StatementLine(LocalDate(2026, 11, 3), -350, "COFFEE SHOP")
        val lines = listOf(coffee, StatementLine(LocalDate(2026, 11, 3), -1250, "MARKET"), coffee)
        val keys = ImportKeys.of(lines)

        // Two coffees at the same price on the same day are two lines: both are booked.
        assertEquals(3, keys.toSet().size)
        // Read again, or found again in the next statement, each has the key it had.
        assertEquals(keys, ImportKeys.of(lines))
        assertEquals(keys.take(2), ImportKeys.of(lines.take(2)))
        // The text's case and accents do not make another line of it; another amount or day does.
        assertEquals(ImportKeys.of(listOf(coffee)), ImportKeys.of(listOf(coffee.copy(text = "Coffee Shop"))))
        assertNotEquals(ImportKeys.of(listOf(coffee)), ImportKeys.of(listOf(coffee.copy(cents = -351))))
        assertNotEquals(ImportKeys.of(listOf(coffee)), ImportKeys.of(listOf(coffee.copy(date = LocalDate(2026, 11, 4)))))
        // With the bank's own id, the id alone names the line, whatever the bank rewrites of its text.
        val byBank = coffee.copy(bankId = "2026110300001")
        assertEquals(ImportKeys.of(listOf(byBank)), ImportKeys.of(listOf(byBank.copy(text = "COFFEE SHOP PAP", cents = -360))))
        // A key is what the server takes: letters, digits and a few signs, 64 at most.
        assertTrue(keys.all { key -> key.length in 1..64 && key.all { it.isLetterOrDigit() || it == '-' } }, keys.toString())
    }

    /** [text] as a French bank's old system writes it: one byte a letter. */
    private fun windows1252(text: String): ByteArray = ByteArray(text.length) { i ->
        when (val c = text[i]) {
            '€' -> 0x80.toByte()
            else -> { require(c.code < 256) { "not in the test's alphabet: $c" }; c.code.toByte() }
        }
    }
}
