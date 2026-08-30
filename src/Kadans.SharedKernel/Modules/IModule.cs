using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;

namespace Kadans.SharedKernel.Modules;

/// <summary>
/// A vertical slice of the application (Identity, Tasks, Budget…). Modules own their
/// services, persistence and endpoints and never reference each other; the host wires them.
/// </summary>
public interface IModule
{
    void AddServices(IServiceCollection services, IConfiguration configuration);

    void MapEndpoints(IEndpointRouteBuilder endpoints);

    /// <summary>Runs once at startup, after the host is built (seeding, warm-up).</summary>
    Task InitializeAsync(IServiceProvider services, CancellationToken cancellationToken) =>
        Task.CompletedTask;
}
