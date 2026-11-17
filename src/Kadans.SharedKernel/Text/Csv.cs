using System.Globalization;
using System.Text;

namespace Kadans.SharedKernel.Text;

/// <summary>
/// A CSV file a spreadsheet opens as it is (RFC 4180): commas, quotes doubled, lines ended by CRLF, UTF-8 with its mark
/// so that Excel reads the accents. Every cell of text is written through <see cref="Cell"/>, which also defuses what a
/// spreadsheet would run: a note is the person's own text, and a line imported from a bank is somebody else's.
/// </summary>
public sealed class Csv
{
    private readonly StringBuilder text = new();

    public Csv(params string[] header) => Row(header);

    /// <summary>One line. A null cell is empty.</summary>
    public Csv Row(params string?[] cells)
    {
        text.AppendJoin(',', cells.Select(c => Cell(c ?? string.Empty))).Append("\r\n");
        return this;
    }

    /// <summary>A number as every spreadsheet reads it: a point for the decimals, no sign for thousands.</summary>
    public static string Number(decimal value) => value.ToString("0.00", CultureInfo.InvariantCulture);

    public static string Number(decimal? value) => value is { } number ? Number(number) : string.Empty;

    /// <summary>The file's bytes: UTF-8, with its byte order mark.</summary>
    public byte[] ToBytes() => [.. Encoding.UTF8.GetPreamble(), .. Encoding.UTF8.GetBytes(text.ToString())];

    public override string ToString() => text.ToString();

    /// <summary>
    /// One cell. A text that begins with =, +, -, @, a tab or a carriage return would be taken for a formula by a
    /// spreadsheet ("=HYPERLINK(...)" in a payee's name): it gets an apostrophe in front, which the spreadsheet shows as
    /// text and does not run. Quoted when it holds a comma, a quote or a line break.
    /// </summary>
    public static string Cell(string value)
    {
        if (value.Length > 0 && value[0] is '=' or '+' or '-' or '@' or '\t' or '\r' && !IsNumber(value))
            value = "'" + value;
        return value.AsSpan().IndexOfAny(",\"\r\n") < 0 ? value : "\"" + value.Replace("\"", "\"\"") + "\"";
    }

    /// <summary>"-12.50" is a number, not a formula: left as it is.</summary>
    private static bool IsNumber(string value) =>
        decimal.TryParse(value, NumberStyles.AllowLeadingSign | NumberStyles.AllowDecimalPoint, CultureInfo.InvariantCulture, out _);
}
