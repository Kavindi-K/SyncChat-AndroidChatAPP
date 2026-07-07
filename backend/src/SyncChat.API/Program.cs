using FirebaseAdmin;
using Google.Apis.Auth.OAuth2;
using Google.Cloud.Firestore;
using Microsoft.OpenApi.Models;
using SyncChat.API.Authentication;
using SyncChat.API.Hubs;
using SyncChat.API.Middleware;
using SyncChat.Application.Interfaces;
using SyncChat.Application.UseCases.Conversations;
using SyncChat.Application.UseCases.Messages;
using SyncChat.Application.UseCases.Users;
using SyncChat.Infrastructure.Repositories;
using SyncChat.Infrastructure.Services;
using Microsoft.AspNetCore.RateLimiting;
using System.Threading.RateLimiting;

AppContext.SetSwitch("System.Net.DisableIPv6", true);
var builder = WebApplication.CreateBuilder(args);
var isTestEnv = builder.Environment.EnvironmentName == "Testing";

// 1. Initialize Firebase Admin SDK (skipped in test environment)
if (!isTestEnv)
{
    if (FirebaseApp.DefaultInstance == null)
    {
        // Option A: Railway/Cloud — credentials JSON provided directly as env var
        var credentialsJson = Environment.GetEnvironmentVariable("FIREBASE_CREDENTIALS_JSON");
        if (!string.IsNullOrEmpty(credentialsJson))
        {
#pragma warning disable CS0618
            using var jsonStream = new MemoryStream(System.Text.Encoding.UTF8.GetBytes(credentialsJson));
            FirebaseApp.Create(new AppOptions { Credential = GoogleCredential.FromStream(jsonStream) });
#pragma warning restore CS0618
        }
        else
        {
            // Option B: Local dev — credentials loaded from file path
            var credentialPath = Environment.GetEnvironmentVariable("GOOGLE_APPLICATION_CREDENTIALS");
            if (string.IsNullOrEmpty(credentialPath))
            {
                if (File.Exists("firebase-service-account.json"))
                    credentialPath = "firebase-service-account.json";
                else if (File.Exists("../firebase-service-account.json"))
                    credentialPath = "../firebase-service-account.json";
                else if (File.Exists("../../firebase-service-account.json"))
                    credentialPath = "../../firebase-service-account.json";
            }

            if (!string.IsNullOrEmpty(credentialPath) && File.Exists(credentialPath))
            {
                var fullPath = Path.GetFullPath(credentialPath);
#pragma warning disable CS0618
                using var stream = File.OpenRead(fullPath);
                FirebaseApp.Create(new AppOptions { Credential = GoogleCredential.FromStream(stream) });
#pragma warning restore CS0618
            }
            else
            {
                FirebaseApp.Create(new AppOptions { Credential = GoogleCredential.GetApplicationDefault() });
            }
        }
    }
}

// 2. Register services
builder.Services.AddControllers();
builder.Services.AddOpenApi();
builder.Services.AddEndpointsApiExplorer();
builder.Services.AddSwaggerGen(c =>
{
    c.SwaggerDoc("v1", new OpenApiInfo { Title = "SyncChat API", Version = "v1" });
    
    var securityScheme = new OpenApiSecurityScheme
    {
        Name = "Authorization",
        Description = "Enter JWT Bearer token: `Bearer {your_token}`",
        In = ParameterLocation.Header,
        Type = SecuritySchemeType.ApiKey,
        Scheme = "Bearer",
        BearerFormat = "JWT",
        Reference = new OpenApiReference
        {
            Id = "Bearer",
            Type = ReferenceType.SecurityScheme
        }
    };
    
    c.AddSecurityDefinition("Bearer", securityScheme);
    c.AddSecurityRequirement(new OpenApiSecurityRequirement
    {
        { securityScheme, Array.Empty<string>() }
    });
});

// Register Firebase auth service (can be replaced by mock in tests)
builder.Services.AddSingleton<IFirebaseAuthService, FirebaseAuthService>();
builder.Services.AddSingleton<IUploadService, FirebaseUploadService>();
builder.Services.AddScoped<INotificationService, FirebaseNotificationService>();

// Register Application UseCases
builder.Services.AddScoped<UserSearchUseCase>();
builder.Services.AddScoped<CreateConversationUseCase>();
builder.Services.AddScoped<SendMessageUseCase>();
builder.Services.AddScoped<GetMessagesUseCase>();

// Configure Rate Limiting
builder.Services.AddRateLimiter(options =>
{
    options.RejectionStatusCode = StatusCodes.Status429TooManyRequests;
    options.AddFixedWindowLimiter("messagePolicy", opt =>
    {
        opt.PermitLimit = 60;
        opt.Window = TimeSpan.FromMinutes(1);
        opt.QueueLimit = 0;
    });
});

// Register Firestore (only created when first resolved — safe for tests since repositories are replaced)
if (!isTestEnv)
{
    builder.Services.AddSingleton(sp =>
    {
        var projectId = builder.Configuration["Firebase:ProjectId"] ?? "syncchat-b0889";
        return FirestoreDb.Create(projectId);
    });
    builder.Services.AddScoped<IUserRepository, FirestoreUserRepository>();
    builder.Services.AddScoped<IConversationRepository, FirestoreConversationRepository>();
    builder.Services.AddScoped<IMessageRepository, FirestoreMessageRepository>();
}

// 3. Configure Firebase Authentication scheme
builder.Services.AddSignalR();
builder.Services.AddAuthentication("Firebase")
    .AddScheme<FirebaseAuthenticationOptions, FirebaseAuthenticationHandler>("Firebase", null);

builder.Services.AddAuthorization();

var app = builder.Build();

app.UseMiddleware<GlobalExceptionMiddleware>();
app.UseStaticFiles();
app.UseRateLimiter();

if (app.Environment.IsDevelopment())
{
    app.MapOpenApi();
    app.UseSwagger();
    app.UseSwaggerUI(c =>
    {
        c.SwaggerEndpoint("/swagger/v1/swagger.json", "SyncChat API v1");
        c.RoutePrefix = "swagger";
    });
}

// 4. Auth middlewares (order matters!)
app.UseAuthentication();
app.UseAuthorization();

app.MapControllers();
app.MapHub<ChatHub>("/hubs/chat");

app.MapGet("/health", () => Results.Ok(new { Status = "Healthy", Timestamp = DateTime.UtcNow }))
   .WithName("GetHealth")
   .AllowAnonymous();

// Protected ping endpoint used by auth middleware tests
app.MapGet("/api/test/ping", () => Results.Ok(new { Message = "pong" }))
   .RequireAuthorization();

app.Run();

// Required for WebApplicationFactory<Program> in tests
public partial class Program { }
