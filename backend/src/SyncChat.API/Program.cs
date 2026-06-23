using FirebaseAdmin;
using Google.Apis.Auth.OAuth2;
using Google.Cloud.Firestore;
using SyncChat.API.Authentication;
using SyncChat.API.Repositories;
using SyncChat.API.Services;

var builder = WebApplication.CreateBuilder(args);
var isTestEnv = builder.Environment.EnvironmentName == "Testing";

// 1. Initialize Firebase Admin SDK (skipped in test environment)
if (!isTestEnv)
{
    var credentialPath = Environment.GetEnvironmentVariable("GOOGLE_APPLICATION_CREDENTIALS");
    if (string.IsNullOrEmpty(credentialPath) && File.Exists("firebase-service-account.json"))
        credentialPath = "firebase-service-account.json";

    if (FirebaseApp.DefaultInstance == null)
    {
        if (!string.IsNullOrEmpty(credentialPath) && File.Exists(credentialPath))
        {
#pragma warning disable CS0618 // GoogleCredential.FromStream — no non-deprecated alternative for AppOptions
            using var stream = File.OpenRead(credentialPath);
            FirebaseApp.Create(new AppOptions { Credential = GoogleCredential.FromStream(stream) });
#pragma warning restore CS0618
        }
        else
        {
            FirebaseApp.Create(new AppOptions { Credential = GoogleCredential.GetApplicationDefault() });
        }
    }
}

// 2. Register services
builder.Services.AddControllers();
builder.Services.AddOpenApi();

// Register Firebase auth service (can be replaced by mock in tests)
builder.Services.AddSingleton<IFirebaseAuthService, FirebaseAuthService>();

// Register Firestore (only created when first resolved — safe for tests since IUserRepository is replaced)
if (!isTestEnv)
{
    builder.Services.AddSingleton(sp =>
    {
        var projectId = builder.Configuration["Firebase:ProjectId"] ?? "syncchat-b0889";
        return FirestoreDb.Create(projectId);
    });
    builder.Services.AddScoped<IUserRepository, UserRepository>();
}

// 3. Configure Firebase Authentication scheme
builder.Services.AddAuthentication("Firebase")
    .AddScheme<FirebaseAuthenticationOptions, FirebaseAuthenticationHandler>("Firebase", null);

builder.Services.AddAuthorization();

var app = builder.Build();

if (app.Environment.IsDevelopment())
    app.MapOpenApi();

// 4. Auth middlewares (order matters!)
app.UseAuthentication();
app.UseAuthorization();

app.MapControllers();

app.MapGet("/health", () => Results.Ok(new { Status = "Healthy", Timestamp = DateTime.UtcNow }))
   .WithName("GetHealth")
   .AllowAnonymous();

// Protected ping endpoint used by auth middleware tests
app.MapGet("/api/test/ping", () => Results.Ok(new { Message = "pong" }))
   .RequireAuthorization();

app.Run();

// Required for WebApplicationFactory<Program> in tests
public partial class Program { }
