using Kadans.SharedKernel.Localization;
using Microsoft.AspNetCore.Identity;

namespace Kadans.Modules.Identity.Security;

/// <summary>
/// ASP.NET Identity's own messages (password policy, duplicate username or email…) in French and
/// Haitian Creole. They carry numbers and names ("at least 8 characters", "'alice' is already taken"),
/// which is why they are translated here, where the parameters are, and not by sentence lookup.
/// English stays the framework's text; every <see cref="IdentityError.Code"/> is untouched.
/// </summary>
internal sealed class LocalizedIdentityErrorDescriber(IRequestLanguage language) : IdentityErrorDescriber
{
    private IdentityError In(IdentityError english, string fr, string ht) =>
        language.Code switch
        {
            "fr" => new IdentityError { Code = english.Code, Description = fr },
            "ht" => new IdentityError { Code = english.Code, Description = ht },
            _ => english,
        };

    public override IdentityError DefaultError() =>
        In(base.DefaultError(), "Une erreur inconnue s'est produite.", "Gen yon erè nou pa konnen ki fèt.");

    public override IdentityError ConcurrencyFailure() =>
        In(base.ConcurrencyFailure(), "Ces données ont été modifiées entre-temps. Réessayez.", "Done sa yo chanje pandan tan sa a. Eseye ankò.");

    public override IdentityError PasswordMismatch() =>
        In(base.PasswordMismatch(), "Mot de passe incorrect.", "Modpas la pa bon.");

    public override IdentityError InvalidToken() =>
        In(base.InvalidToken(), "Ce lien ou ce code est invalide ou expiré.", "Lyen sa a oswa kòd sa a pa valab oswa li ekspire.");

    public override IdentityError RecoveryCodeRedemptionFailed() =>
        In(base.RecoveryCodeRedemptionFailed(), "Ce code de récupération n'est pas valide.", "Kòd rekiperasyon sa a pa valab.");

    public override IdentityError LoginAlreadyAssociated() =>
        In(base.LoginAlreadyAssociated(), "Cette connexion est déjà associée à un autre compte.", "Koneksyon sa a deja mare ak yon lòt kont.");

    public override IdentityError InvalidUserName(string? userName) =>
        In(
            base.InvalidUserName(userName),
            $"Le nom d'utilisateur « {userName} » n'est pas valide : lettres et chiffres uniquement.",
            $"Non itilizatè « {userName} » pa valab: se lèt ak chif sèlman."
        );

    public override IdentityError InvalidEmail(string? email) =>
        In(base.InvalidEmail(email), $"L'adresse e-mail « {email} » n'est pas valide.", $"Adrès imèl « {email} » pa valab.");

    public override IdentityError DuplicateUserName(string userName) =>
        In(base.DuplicateUserName(userName), $"Le nom d'utilisateur « {userName} » est déjà pris.", $"Non itilizatè « {userName} » deja pran.");

    public override IdentityError DuplicateEmail(string email) =>
        In(base.DuplicateEmail(email), $"L'adresse e-mail « {email} » est déjà utilisée.", $"Adrès imèl « {email} » deja itilize.");

    public override IdentityError InvalidRoleName(string? role) =>
        In(base.InvalidRoleName(role), $"Le nom de rôle « {role} » n'est pas valide.", $"Non wòl « {role} » pa valab.");

    public override IdentityError DuplicateRoleName(string role) =>
        In(base.DuplicateRoleName(role), $"Le rôle « {role} » existe déjà.", $"Wòl « {role} » egziste deja.");

    public override IdentityError UserAlreadyHasPassword() =>
        In(base.UserAlreadyHasPassword(), "Ce compte a déjà un mot de passe.", "Kont sa a gen yon modpas deja.");

    public override IdentityError UserLockoutNotEnabled() =>
        In(base.UserLockoutNotEnabled(), "Le verrouillage n'est pas activé pour ce compte.", "Bloke kont pa aktive pou kont sa a.");

    public override IdentityError UserAlreadyInRole(string role) =>
        In(base.UserAlreadyInRole(role), $"Cet utilisateur a déjà le rôle « {role} ».", $"Itilizatè sa a gen wòl « {role} » deja.");

    public override IdentityError UserNotInRole(string role) =>
        In(base.UserNotInRole(role), $"Cet utilisateur n'a pas le rôle « {role} ».", $"Itilizatè sa a pa gen wòl « {role} ».");

    public override IdentityError PasswordTooShort(int length) =>
        In(
            base.PasswordTooShort(length),
            $"Le mot de passe doit contenir au moins {length} caractères.",
            $"Modpas la dwe gen omwen {length} karaktè."
        );

    public override IdentityError PasswordRequiresUniqueChars(int uniqueChars) =>
        In(
            base.PasswordRequiresUniqueChars(uniqueChars),
            $"Le mot de passe doit contenir au moins {uniqueChars} caractères différents.",
            $"Modpas la dwe gen omwen {uniqueChars} karaktè diferan."
        );

    public override IdentityError PasswordRequiresNonAlphanumeric() =>
        In(
            base.PasswordRequiresNonAlphanumeric(),
            "Le mot de passe doit contenir au moins un caractère spécial (ni lettre ni chiffre).",
            "Modpas la dwe gen omwen yon karaktè espesyal (ki pa ni lèt ni chif)."
        );

    public override IdentityError PasswordRequiresDigit() =>
        In(base.PasswordRequiresDigit(), "Le mot de passe doit contenir au moins un chiffre (0-9).", "Modpas la dwe gen omwen yon chif (0-9).");

    public override IdentityError PasswordRequiresLower() =>
        In(base.PasswordRequiresLower(), "Le mot de passe doit contenir au moins une minuscule (a-z).", "Modpas la dwe gen omwen yon lèt miniskil (a-z).");

    public override IdentityError PasswordRequiresUpper() =>
        In(base.PasswordRequiresUpper(), "Le mot de passe doit contenir au moins une majuscule (A-Z).", "Modpas la dwe gen omwen yon lèt majiskil (A-Z).");
}
