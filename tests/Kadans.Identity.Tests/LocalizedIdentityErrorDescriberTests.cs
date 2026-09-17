using Kadans.Modules.Identity.Security;
using Kadans.SharedKernel.Localization;
using Microsoft.AspNetCore.Identity;

namespace Kadans.Identity.Tests;

public class LocalizedIdentityErrorDescriberTests
{
    private sealed class Fixed(string code) : IRequestLanguage
    {
        public string Code => code;
    }

    [Test]
    public async Task Password_rules_keep_their_numbers_in_every_language()
    {
        await Assert.That(new LocalizedIdentityErrorDescriber(new Fixed("fr")).PasswordTooShort(8).Description).IsEqualTo("Le mot de passe doit contenir au moins 8 caractères.");
        await Assert.That(new LocalizedIdentityErrorDescriber(new Fixed("ht")).PasswordTooShort(8).Description).IsEqualTo("Modpas la dwe gen omwen 8 karaktè.");
        await Assert.That(new LocalizedIdentityErrorDescriber(new Fixed("en")).PasswordTooShort(8).Description).IsEqualTo(new IdentityErrorDescriber().PasswordTooShort(8).Description);
    }

    [Test]
    public async Task Names_are_kept_and_codes_never_change()
    {
        var creole = new LocalizedIdentityErrorDescriber(new Fixed("ht")).DuplicateUserName("alice");

        await Assert.That(creole.Description).Contains("alice");
        await Assert.That(creole.Code).IsEqualTo(nameof(IdentityErrorDescriber.DuplicateUserName));
    }

    [Test]
    public async Task Every_message_identity_can_produce_is_translated()
    {
        var english = new IdentityErrorDescriber();
        var french = new LocalizedIdentityErrorDescriber(new Fixed("fr"));
        var methods = typeof(IdentityErrorDescriber).GetMethods().Where(m => m.ReturnType == typeof(IdentityError)).ToList();

        var untranslated = new List<string>();
        foreach (var method in methods)
        {
            var arguments = method.GetParameters().Select(p => p.ParameterType == typeof(int) ? (object)8 : "x").ToArray();
            var en = (IdentityError)method.Invoke(english, arguments)!;
            var fr = (IdentityError)method.Invoke(french, arguments)!;
            if (fr.Description == en.Description)
                untranslated.Add(method.Name);
            await Assert.That(fr.Code).IsEqualTo(en.Code);
        }

        await Assert.That(methods.Count).IsGreaterThan(15);
        await Assert.That(untranslated).IsEmpty();
    }
}
