using Kadans.Api.DTOs;
using Kadans.Api.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Http.HttpResults;
using OneOf.Types;

namespace Kadans.Api.Routes;

public static class AuthRoutes
{
    extension(IEndpointRouteBuilder routeBuilder)
    {
        public void MapAuthRoutes()
        {
            var authGroup = routeBuilder.MapGroup("/auth").WithTags("Auth");

            authGroup
                .MapPost(
                    "/login",
                    async Task<Results<Ok<LoginResponse>, ProblemHttpResult>> (
                        LoginRequest request,
                        Authentication service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.Login(request);
                        return result.Match<Results<Ok<LoginResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            response => TypedResults.Ok(response)
                        );
                    }
                )
                .AllowAnonymous()
                .WithName("AuthLogin")
                .WithSummary("Sign in with username and password")
                .WithDescription(
                    "Validates user credentials and issues an access token and a refresh token."
                )
                .Produces<LoginResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status401Unauthorized)
                .ProducesProblem(StatusCodes.Status403Forbidden);

            authGroup
                .MapPost(
                    "/refresh",
                    async Task<Results<Ok<LoginResponse>, ProblemHttpResult>> (
                        RefreshTokenRequest request,
                        Authentication service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.RefreshToken(request);
                        return result.Match<Results<Ok<LoginResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            response => TypedResults.Ok(response)
                        );
                    }
                )
                .AllowAnonymous()
                .WithName("AuthRefresh")
                .WithSummary("Refresh access token")
                .WithDescription(
                    "Validates a refresh token, rotates it, and returns a new access token and refresh token pair."
                )
                .Produces<LoginResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status401Unauthorized)
                .ProducesProblem(StatusCodes.Status403Forbidden);

            authGroup
                .MapPost(
                    "/register",
                    async Task<Results<Ok<UserResponse>, ProblemHttpResult>> (
                        RegisterUserRequest request,
                        UserManagement service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.RegisterUser(request);
                        return result.Match<Results<Ok<UserResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            response => TypedResults.Ok(response)
                        );
                    }
                )
                .AllowAnonymous()
                .WithName("AuthRegister")
                .WithSummary("Register a user")
                .WithDescription("Registers a new user account without role assignment.")
                .Produces<UserResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status403Forbidden);

            authGroup
                .MapPost(
                    "/revoke",
                    async Task<Ok<Success>> (
                        RevokeRefreshTokenRequest request,
                        Authentication service
                    ) =>
                    {
                        await service.RevokeRefreshToken(request.RefreshToken);
                        return TypedResults.Ok(new Success());
                    }
                )
                .RequireAuthorization()
                .WithName("AuthRevoke")
                .WithSummary("Revoke refresh token")
                .WithDescription("Revokes a refresh token so it can no longer be used.")
                .Produces<Success>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status401Unauthorized);
        }

        public void MapUserRoutes()
        {
            var selfUsersGroup = routeBuilder
                .MapGroup("/users/me")
                .WithTags("Users")
                .RequireAuthorization();

            selfUsersGroup
                .MapPut(
                    string.Empty,
                    async Task<Results<Ok<UserResponse>, ProblemHttpResult>> (
                        UpdateSelfUserRequest request,
                        UserManagement service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.UpdateCurrentUser(request);
                        return result.Match<Results<Ok<UserResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            response => TypedResults.Ok(response)
                        );
                    }
                )
                .WithName("UsersUpdateSelf")
                .WithSummary("Update own profile")
                .WithDescription("Updates the authenticated user's username, email, or password.")
                .Produces<UserResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status401Unauthorized)
                .ProducesProblem(StatusCodes.Status403Forbidden)
                .ProducesProblem(StatusCodes.Status404NotFound);

            selfUsersGroup
                .MapPut(
                    "/deactivate",
                    async Task<Results<Ok<UserResponse>, ProblemHttpResult>> (
                        UserManagement service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.DeactivateCurrentUser();
                        return result.Match<Results<Ok<UserResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            response => TypedResults.Ok(response)
                        );
                    }
                )
                .WithName("UsersDeactivateSelf")
                .WithSummary("Deactivate own account")
                .WithDescription(
                    "Deactivates the authenticated user account and revokes active refresh tokens."
                )
                .Produces<UserResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status401Unauthorized)
                .ProducesProblem(StatusCodes.Status403Forbidden)
                .ProducesProblem(StatusCodes.Status404NotFound);

            var adminUsersGroup = routeBuilder
                .MapGroup("/users")
                .WithTags("Users")
                .RequireAuthorization(new AuthorizeAttribute { Roles = "Admin" });

            adminUsersGroup
                .MapPost(
                    string.Empty,
                    async Task<Results<Ok<UserResponse>, ProblemHttpResult>> (
                        CreateUserRequest request,
                        UserManagement service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.CreateUser(request);
                        return result.Match<Results<Ok<UserResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            response => TypedResults.Ok(response)
                        );
                    }
                )
                .WithName("UsersCreate")
                .WithSummary("Create a user")
                .WithDescription(
                    "Creates a new user account and optionally assigns existing roles."
                )
                .Produces<UserResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status401Unauthorized)
                .ProducesProblem(StatusCodes.Status403Forbidden);

            adminUsersGroup
                .MapPut(
                    "/{userId}",
                    async Task<Results<Ok<UserResponse>, ProblemHttpResult>> (
                        string userId,
                        UpdateUserRequest request,
                        UserManagement service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.UpdateUser(userId, request);
                        return result.Match<Results<Ok<UserResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            response => TypedResults.Ok(response)
                        );
                    }
                )
                .WithName("UsersUpdate")
                .WithSummary("Update a user")
                .WithDescription(
                    "Updates username, email, password, and role memberships for an existing user."
                )
                .Produces<UserResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status401Unauthorized)
                .ProducesProblem(StatusCodes.Status403Forbidden)
                .ProducesProblem(StatusCodes.Status404NotFound);

            adminUsersGroup
                .MapPut(
                    "/{userId}/deactivate",
                    async Task<Results<Ok<UserResponse>, ProblemHttpResult>> (
                        string userId,
                        UserManagement service,
                        HttpContext context
                    ) =>
                    {
                        var result = await service.DeactivateUser(userId);
                        return result.Match<Results<Ok<UserResponse>, ProblemHttpResult>>(
                            error =>
                                TypedResults.Problem(error.ToProblemDetails(context.Request.Path)),
                            response => TypedResults.Ok(response)
                        );
                    }
                )
                .WithName("UsersDeactivate")
                .WithSummary("Deactivate a user")
                .WithDescription(
                    "Disables a user account by setting lockout, updates security stamp, and revokes active refresh tokens."
                )
                .Produces<UserResponse>(StatusCodes.Status200OK)
                .ProducesProblem(StatusCodes.Status400BadRequest)
                .ProducesProblem(StatusCodes.Status401Unauthorized)
                .ProducesProblem(StatusCodes.Status403Forbidden)
                .ProducesProblem(StatusCodes.Status404NotFound);
        }
    }
}
