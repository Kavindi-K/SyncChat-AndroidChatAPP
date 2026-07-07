# Build Stage
FROM mcr.microsoft.com/dotnet/sdk:9.0 AS build-env
WORKDIR /app

# Copy csproj and restore dependencies
COPY src/SyncChat.API/SyncChat.API.csproj ./src/SyncChat.API/
RUN dotnet restore ./src/SyncChat.API/SyncChat.API.csproj

# Copy everything else and build
COPY . ./
RUN dotnet publish ./src/SyncChat.API/SyncChat.API.csproj -c Release -o out

# Runtime Stage
FROM mcr.microsoft.com/dotnet/aspnet:9.0
WORKDIR /app
COPY --from=build-env /app/out .

# Expose port (Railway injects $PORT, fallback to 8080)
EXPOSE 8080
ENV ASPNETCORE_URLS=http://+:${PORT:-8080}
ENV ASPNETCORE_ENVIRONMENT=Production

ENTRYPOINT ["dotnet", "SyncChat.API.dll"]
