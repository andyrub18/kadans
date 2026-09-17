using System.Text.RegularExpressions;
using Kadans.SharedKernel.Errors;
using Kadans.SharedKernel.Localization;

namespace Kadans.SharedKernel.Tests;

public class ErrorLocalizationTests
{
    [Test]
    [Arguments(null, "en")]
    [Arguments("", "en")]
    [Arguments("ht", "ht")]
    [Arguments("fr-HT", "fr")]
    [Arguments("fr_CA", "fr")]
    [Arguments("HT", "ht")]
    [Arguments("de, es;q=0.9", "en")]
    [Arguments("de, ht;q=0.8, fr;q=0.9", "fr")]
    [Arguments("fr;q=0, ht", "ht")]
    [Arguments("en-US,en;q=0.9,fr;q=0.8", "en")]
    [Arguments("*", "en")]
    public async Task Accept_Language_resolves_to_a_language_kadans_speaks(string? header, string expected)
    {
        await Assert.That(RequestLanguage.Resolve(header)).IsEqualTo(expected);
    }

    [Test]
    public async Task Every_error_type_has_a_french_and_a_creole_sentence()
    {
        var missing = ErrorTypes.List
            .Where(type => !ErrorTexts.ByType.TryGetValue(type.Value, out var text) || string.IsNullOrWhiteSpace(text.Fr) || string.IsNullOrWhiteSpace(text.Ht))
            .Select(type => $"{type.Value} {type.Name}")
            .ToList();

        await Assert.That(missing).IsEmpty();
    }

    [Test]
    public async Task A_known_sentence_is_translated_and_english_is_never_touched()
    {
        var error = new ApplicationError(ErrorTypes.InvalidAmount, "Amount must be greater than zero.");

        await Assert.That(error.ToProblemDetails("/x").Detail).IsEqualTo("Amount must be greater than zero.");
        await Assert.That(error.ToProblemDetails("/x", "fr").Detail).IsEqualTo("Le montant doit être supérieur à zéro.");
        await Assert.That(error.ToProblemDetails("/x", "ht").Detail).IsEqualTo("Montan an dwe pi gran pase zewo.");
    }

    [Test]
    public async Task A_message_carrying_an_id_falls_back_to_its_error_types_sentence()
    {
        var error = new ApplicationError(ErrorTypes.TodoNotFound, $"Todo with id {Guid.NewGuid()} not found");

        await Assert.That(error.ToProblemDetails("/x", "ht").Detail).IsEqualTo("Nou pa jwenn travay la.");
        await Assert.That(error.ToProblemDetails("/x", "en").Detail!).Contains("Todo with id");
    }

    [Test]
    public async Task The_code_is_the_contract_whatever_the_language()
    {
        var error = new ApplicationError(ErrorTypes.InvalidCredentials, "Invalid username or password");

        foreach (var language in RequestLanguage.Supported)
        {
            var problem = error.ToProblemDetails("/auth/login", language);
            await Assert.That(problem.Extensions["errorCode"]).IsEqualTo("10024");
            await Assert.That(problem.Status).IsEqualTo(401);
            await Assert.That(problem.Title).IsEqualTo(ErrorTypes.InvalidCredentials.Name);
        }
    }

    [Test]
    public async Task Validation_entries_are_translated_one_by_one_and_keep_their_codes()
    {
        var error = new ValidationError(
            ErrorTypes.ValidationError,
            "Validation failed for creating recurring todo.",
            [
                (ErrorTypes.TitleRequired.Value, "Title is required."),
                (ErrorTypes.InvalidHour.Value, "Some new hour rule nobody translated yet."),
                ("PasswordTooShort", "Le mot de passe doit contenir au moins 8 caractères."), // Identity's describer already did it
            ]
        );

        var problem = error.ToProblemDetails("/todos/recurring", "fr");
        var entries = System.Text.Json.JsonSerializer.Serialize(
            problem.Extensions["errors"],
            new System.Text.Json.JsonSerializerOptions { Encoder = System.Text.Encodings.Web.JavaScriptEncoder.UnsafeRelaxedJsonEscaping }
        );

        await Assert.That(problem.Detail).IsEqualTo("Certaines informations ne sont pas valides."); // not the developer's label
        await Assert.That(entries).Contains("Le titre est obligatoire.");
        await Assert.That(entries).Contains("L'heure n'est pas valide."); // unknown sentence → its code's sentence
        await Assert.That(entries).Contains("au moins 8 caract");
        await Assert.That(entries).Contains("\"10017\"");
        await Assert.That(entries).Contains("\"PasswordTooShort\"");
    }

    /// <summary>
    /// The server-side twin of the client's compile-checked catalog: a static English message anywhere in
    /// <c>src/</c> without a French and Creole sentence in <see cref="ErrorTexts"/> fails the build here.
    /// (Interpolated messages are covered by their error type's sentence, asserted above.)
    /// </summary>
    [Test]
    public async Task Every_static_error_message_in_the_source_is_translated()
    {
        var root = new DirectoryInfo(AppContext.BaseDirectory);
        while (root is not null && !File.Exists(Path.Combine(root.FullName, "Kadans.slnx")))
            root = root.Parent;
        await Assert.That(root).IsNotNull();

        var message = new Regex(
            """(?:new\s+(?:ApplicationError|ValidationError)\(\s*ErrorTypes\.\w+\s*,|\.WithMessage\(|\bInvalidState\(|\bInvalid\()\s*"((?:[^"\\]|\\.)*)"\s*[,)]""",
            RegexOptions.Singleline
        );
        var untranslated = Directory
            .EnumerateFiles(Path.Combine(root!.FullName, "src"), "*.cs", SearchOption.AllDirectories)
            .Where(file => !file.Contains($"{Path.DirectorySeparatorChar}obj{Path.DirectorySeparatorChar}") && !file.Contains($"{Path.DirectorySeparatorChar}bin{Path.DirectorySeparatorChar}"))
            .SelectMany(file => message.Matches(File.ReadAllText(file)).Select(match => (File: Path.GetFileName(file), Text: Regex.Unescape(match.Groups[1].Value))))
            .Where(found => !ErrorTexts.HasSentence(found.Text))
            .Select(found => $"{found.File}: {found.Text}")
            .Distinct()
            .ToList();

        await Assert.That(untranslated).IsEmpty();
    }
}
