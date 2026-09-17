# Kadans API – one image for any host that runs containers (a VPS with Docker Compose, Fly.io, Render…).
#   docker build -t kadans-api .
# Configuration comes from environment variables (see deploy/.env.example); nothing secret is baked in.

FROM mcr.microsoft.com/dotnet/sdk:10.0 AS build
WORKDIR /src

# Restore first, on project files only: this layer is reused until a dependency changes.
COPY global.json Directory.Build.props Directory.Packages.props ./
COPY src/Kadans.Api/Kadans.Api.csproj src/Kadans.Api/
COPY src/Kadans.SharedKernel/Kadans.SharedKernel.csproj src/Kadans.SharedKernel/
COPY src/Kadans.Modules.Identity/Kadans.Modules.Identity.csproj src/Kadans.Modules.Identity/
COPY src/Kadans.Modules.Tasks/Kadans.Modules.Tasks.csproj src/Kadans.Modules.Tasks/
COPY src/Kadans.Modules.Notifications/Kadans.Modules.Notifications.csproj src/Kadans.Modules.Notifications/
COPY src/Kadans.Modules.Budget/Kadans.Modules.Budget.csproj src/Kadans.Modules.Budget/
RUN dotnet restore src/Kadans.Api/Kadans.Api.csproj

COPY src/ src/
RUN dotnet publish src/Kadans.Api/Kadans.Api.csproj --configuration Release --no-restore --output /app

FROM mcr.microsoft.com/dotnet/aspnet:10.0
WORKDIR /app
COPY --from=build /app .

# 8080 behind a TLS-terminating proxy: trust its X-Forwarded-* headers so links and redirects say https.
# Exactly one instance runs (in-memory scheduler, push queue and SignalR), so migrating at startup is safe.
ENV ASPNETCORE_HTTP_PORTS=8080 \
    ASPNETCORE_FORWARDEDHEADERS_ENABLED=true \
    Database__MigrateOnStartup=true
EXPOSE 8080
USER $APP_UID
ENTRYPOINT ["dotnet", "Kadans.Api.dll"]
