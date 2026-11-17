using System.Text;
using Kadans.SharedKernel.Text;

namespace Kadans.SharedKernel.Tests;

/// <summary>The CSV files of "Export my data": what a spreadsheet opens, and never runs.</summary>
public class CsvTests
{
    [Test]
    public async Task A_cell_is_quoted_only_when_it_has_to_be()
    {
        await Assert.That(Csv.Cell("Groceries")).IsEqualTo("Groceries");
        await Assert.That(Csv.Cell("Rice, oil and beans")).IsEqualTo("\"Rice, oil and beans\"");
        await Assert.That(Csv.Cell("The \"big\" one")).IsEqualTo("\"The \"\"big\"\" one\"");
        await Assert.That(Csv.Cell("two\nlines")).IsEqualTo("\"two\nlines\"");
        await Assert.That(Csv.Cell("")).IsEqualTo("");
    }

    [Test]
    public async Task A_text_a_spreadsheet_would_run_is_written_as_text()
    {
        // A payee's name on an imported statement, or a note: opened in Excel, "=..." is a formula.
        await Assert.That(Csv.Cell("=HYPERLINK(\"http://evil.example\",\"Refund\")")).IsEqualTo("\"'=HYPERLINK(\"\"http://evil.example\"\",\"\"Refund\"\")\"");
        await Assert.That(Csv.Cell("+1 809 555 0100")).IsEqualTo("'+1 809 555 0100");
        await Assert.That(Csv.Cell("@SUM(A1:A9)")).IsEqualTo("'@SUM(A1:A9)");
        await Assert.That(Csv.Cell("-cmd|' /C calc'!A0")).IsEqualTo("'-cmd|' /C calc'!A0");
        await Assert.That(Csv.Cell("\tTabbed")).IsEqualTo("'\tTabbed");
        // A plain negative number is a number.
        await Assert.That(Csv.Cell("-12.50")).IsEqualTo("-12.50");
        // Elsewhere in the text the same signs are just text.
        await Assert.That(Csv.Cell("Rent = half")).IsEqualTo("Rent = half");
    }

    [Test]
    public async Task A_file_has_its_header_crlf_lines_and_the_mark_excel_needs_for_accents()
    {
        var csv = new Csv("Date", "Note", "Amount").Row("2026-11-16", "Café, déjeuner", Csv.Number(1250.5m)).Row("2026-11-17", null, Csv.Number((decimal?)null));

        await Assert.That(csv.ToString()).IsEqualTo("Date,Note,Amount\r\n2026-11-16,\"Café, déjeuner\",1250.50\r\n2026-11-17,,\r\n");
        var bytes = csv.ToBytes();
        await Assert.That(bytes.Take(3).ToArray()).IsEquivalentTo(new byte[] { 0xEF, 0xBB, 0xBF });
        await Assert.That(Encoding.UTF8.GetString(bytes, 3, bytes.Length - 3)).IsEqualTo(csv.ToString());
    }

    [Test]
    public async Task A_number_reads_the_same_in_every_country()
    {
        // No thousands sign, a point for the decimals, always two: whatever the server's own language is.
        await Assert.That(Csv.Number(1234567.8m)).IsEqualTo("1234567.80");
        await Assert.That(Csv.Number(0m)).IsEqualTo("0.00");
    }
}
