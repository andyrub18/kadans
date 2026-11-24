using Kadans.SharedKernel.Localization;
using Microsoft.AspNetCore.Identity;

namespace Kadans.Modules.Identity.Security;

/// <summary>
/// ASP.NET Identity's own messages (password policy, duplicate username or email…) in French,
/// Haitian Creole and Spanish. They carry numbers and names ("at least 8 characters", "'alice' is already taken"),
/// which is why they are translated here, where the parameters are, and not by sentence lookup.
/// English stays the framework's text; every <see cref="IdentityError.Code"/> is untouched.
/// </summary>
internal sealed class LocalizedIdentityErrorDescriber(IRequestLanguage language) : IdentityErrorDescriber
{
    private IdentityError In(IdentityError english, string fr, string ht, string es) =>
        language.Code switch
        {
            "fr" => new IdentityError { Code = english.Code, Description = fr },
            "ht" => new IdentityError { Code = english.Code, Description = ht },
            "es" => new IdentityError { Code = english.Code, Description = es },
            _ => english,
        };

    public override IdentityError DefaultError() =>
        In(base.DefaultError(), "Une erreur inconnue s'est produite.", "Gen yon erè nou pa konnen ki fèt.", "Ocurrió un error desconocido.");

    public override IdentityError ConcurrencyFailure() =>
        In(base.ConcurrencyFailure(), "Ces données ont été modifiées entre-temps. Réessayez.", "Done sa yo chanje pandan tan sa a. Eseye ankò.", "Estos datos cambiaron mientras tanto. Inténtalo de nuevo.");

    public override IdentityError PasswordMismatch() =>
        In(base.PasswordMismatch(), "Mot de passe incorrect.", "Modpas la pa bon.", "Contraseña incorrecta.");

    public override IdentityError InvalidToken() =>
        In(base.InvalidToken(), "Ce lien ou ce code est invalide ou expiré.", "Lyen sa a oswa kòd sa a pa valab oswa li ekspire.", "Este enlace o este código no es válido o ya expiró.");

    public override IdentityError RecoveryCodeRedemptionFailed() =>
        In(base.RecoveryCodeRedemptionFailed(), "Ce code de récupération n'est pas valide.", "Kòd rekiperasyon sa a pa valab.", "Este código de recuperación no es válido.");

    public override IdentityError LoginAlreadyAssociated() =>
        In(base.LoginAlreadyAssociated(), "Cette connexion est déjà associée à un autre compte.", "Koneksyon sa a deja mare ak yon lòt kont.", "Este inicio de sesión ya está vinculado a otra cuenta.");

    public override IdentityError InvalidUserName(string? userName) =>
        In(
            base.InvalidUserName(userName),
            $"Le nom d'utilisateur « {userName} » n'est pas valide : lettres et chiffres uniquement.",
            $"Non itilizatè « {userName} » pa valab: se lèt ak chif sèlman.",
            $"El nombre de usuario “{userName}” no es válido: solo letras y números."
        );

    public override IdentityError InvalidEmail(string? email) =>
        In(base.InvalidEmail(email), $"L'adresse e-mail « {email} » n'est pas valide.", $"Adrès imèl « {email} » pa valab.", $"El correo electrónico “{email}” no es válido.");

    public override IdentityError DuplicateUserName(string userName) =>
        In(base.DuplicateUserName(userName), $"Le nom d'utilisateur « {userName} » est déjà pris.", $"Non itilizatè « {userName} » deja pran.", $"El nombre de usuario “{userName}” ya está en uso.");

    public override IdentityError DuplicateEmail(string email) =>
        In(base.DuplicateEmail(email), $"L'adresse e-mail « {email} » est déjà utilisée.", $"Adrès imèl « {email} » deja itilize.", $"El correo electrónico “{email}” ya está en uso.");

    public override IdentityError InvalidRoleName(string? role) =>
        In(base.InvalidRoleName(role), $"Le nom de rôle « {role} » n'est pas valide.", $"Non wòl « {role} » pa valab.", $"El nombre de rol “{role}” no es válido.");

    public override IdentityError DuplicateRoleName(string role) =>
        In(base.DuplicateRoleName(role), $"Le rôle « {role} » existe déjà.", $"Wòl « {role} » egziste deja.", $"El rol “{role}” ya existe.");

    public override IdentityError UserAlreadyHasPassword() =>
        In(base.UserAlreadyHasPassword(), "Ce compte a déjà un mot de passe.", "Kont sa a gen yon modpas deja.", "Esta cuenta ya tiene una contraseña.");

    public override IdentityError UserLockoutNotEnabled() =>
        In(base.UserLockoutNotEnabled(), "Le verrouillage n'est pas activé pour ce compte.", "Bloke kont pa aktive pou kont sa a.", "El bloqueo no está activado para esta cuenta.");

    public override IdentityError UserAlreadyInRole(string role) =>
        In(base.UserAlreadyInRole(role), $"Cet utilisateur a déjà le rôle « {role} ».", $"Itilizatè sa a gen wòl « {role} » deja.", $"Este usuario ya tiene el rol “{role}”.");

    public override IdentityError UserNotInRole(string role) =>
        In(base.UserNotInRole(role), $"Cet utilisateur n'a pas le rôle « {role} ».", $"Itilizatè sa a pa gen wòl « {role} ».", $"Este usuario no tiene el rol “{role}”.");

    public override IdentityError PasswordTooShort(int length) =>
        In(
            base.PasswordTooShort(length),
            $"Le mot de passe doit contenir au moins {length} caractères.",
            $"Modpas la dwe gen omwen {length} karaktè.",
            $"La contraseña debe tener al menos {length} caracteres."
        );

    public override IdentityError PasswordRequiresUniqueChars(int uniqueChars) =>
        In(
            base.PasswordRequiresUniqueChars(uniqueChars),
            $"Le mot de passe doit contenir au moins {uniqueChars} caractères différents.",
            $"Modpas la dwe gen omwen {uniqueChars} karaktè diferan.",
            $"La contraseña debe tener al menos {uniqueChars} caracteres diferentes."
        );

    public override IdentityError PasswordRequiresNonAlphanumeric() =>
        In(
            base.PasswordRequiresNonAlphanumeric(),
            "Le mot de passe doit contenir au moins un caractère spécial (ni lettre ni chiffre).",
            "Modpas la dwe gen omwen yon karaktè espesyal (ki pa ni lèt ni chif).",
            "La contraseña debe tener al menos un carácter especial (ni letra ni número)."
        );

    public override IdentityError PasswordRequiresDigit() =>
        In(base.PasswordRequiresDigit(), "Le mot de passe doit contenir au moins un chiffre (0-9).", "Modpas la dwe gen omwen yon chif (0-9).", "La contraseña debe tener al menos un número (0-9).");

    public override IdentityError PasswordRequiresLower() =>
        In(base.PasswordRequiresLower(), "Le mot de passe doit contenir au moins une minuscule (a-z).", "Modpas la dwe gen omwen yon lèt miniskil (a-z).", "La contraseña debe tener al menos una minúscula (a-z).");

    public override IdentityError PasswordRequiresUpper() =>
        In(base.PasswordRequiresUpper(), "Le mot de passe doit contenir au moins une majuscule (A-Z).", "Modpas la dwe gen omwen yon lèt majiskil (A-Z).", "La contraseña debe tener al menos una mayúscula (A-Z).");
}
