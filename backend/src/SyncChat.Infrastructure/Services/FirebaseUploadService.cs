using System;
using System.IO;
using System.Net.Http;
using System.Threading.Tasks;
using Google.Apis.Auth.OAuth2;
using Google.Cloud.Storage.V1;
using SyncChat.Application.Interfaces;

namespace SyncChat.Infrastructure.Services;

public class FirebaseUploadService : IUploadService
{
    private readonly string _bucketName;
    private readonly GoogleCredential? _credential;

    public FirebaseUploadService()
    {
        var credentialPath = Environment.GetEnvironmentVariable("GOOGLE_APPLICATION_CREDENTIALS");
        if (!string.IsNullOrEmpty(credentialPath) && File.Exists(credentialPath))
        {
            using var stream = File.OpenRead(credentialPath);
            _credential = GoogleCredential.FromStream(stream);
        }
        else
        {
            _credential = GoogleCredential.GetApplicationDefault();
        }

        var projectId = Environment.GetEnvironmentVariable("FIREBASE_PROJECT_ID") ?? "syncchat-b0889";
        _bucketName = Environment.GetEnvironmentVariable("FIREBASE_STORAGE_BUCKET") ?? $"{projectId}.firebasestorage.app";
    }

    // For unit testing/mocking injection
    public FirebaseUploadService(GoogleCredential credential, string bucketName)
    {
        _credential = credential;
        _bucketName = bucketName;
    }

    public async Task<(string UploadUrl, string DownloadUrl)> GenerateSignedUrlAsync(string userId, string fileName, string contentType)
    {
        if (string.IsNullOrWhiteSpace(userId))
            throw new ArgumentException("UserId is required", nameof(userId));
        if (string.IsNullOrWhiteSpace(fileName))
            throw new ArgumentException("FileName is required", nameof(fileName));
        if (string.IsNullOrWhiteSpace(contentType))
            throw new ArgumentException("ContentType is required", nameof(contentType));

        var uniqueId = Guid.NewGuid().ToString("N");
        var fileExtension = Path.GetExtension(fileName);
        var objectName = $"uploads/{userId}/{uniqueId}{fileExtension}";

        var emulatorHost = Environment.GetEnvironmentVariable("FIREBASE_STORAGE_EMULATOR_HOST");
        var isDevelopment = Environment.GetEnvironmentVariable("ASPNETCORE_ENVIRONMENT") == "Development";

        if (!string.IsNullOrEmpty(emulatorHost))
        {
            var escapedObjectName = Uri.EscapeDataString(objectName);
            var emuUploadUrl = $"http://{emulatorHost}/v0/b/{_bucketName}/o?name={escapedObjectName}";
            var emuDownloadUrl = $"http://{emulatorHost}/v0/b/{_bucketName}/o/{escapedObjectName}?alt=media";
            return (emuUploadUrl, emuDownloadUrl);
        }
        else if (isDevelopment)
        {
            var devObjectName = $"{uniqueId}{fileExtension}";
            
            var devUploadUrl = $"http://localhost:5228/api/mockstorage?userId={Uri.EscapeDataString(userId)}&fileName={Uri.EscapeDataString(devObjectName)}&contentType={Uri.EscapeDataString(contentType)}";
            var devDownloadUrl = $"http://localhost:5228/mockstorage/uploads/{userId}/{devObjectName}";
            return (devUploadUrl, devDownloadUrl);
        }

        var urlSigner = UrlSigner.FromCredential(_credential);
        
        var requestTemplate = UrlSigner.RequestTemplate
            .FromBucket(_bucketName)
            .WithObjectName(objectName)
            .WithHttpMethod(HttpMethod.Put)
            .WithContentHeaders(new[] { new System.Collections.Generic.KeyValuePair<string, System.Collections.Generic.IEnumerable<string>>("Content-Type", new[] { contentType }) });

        var options = UrlSigner.Options.FromDuration(TimeSpan.FromMinutes(15));

        var uploadUrl = await urlSigner.SignAsync(requestTemplate, options);

        var downloadUrl = $"https://firebasestorage.googleapis.com/v0/b/{_bucketName}/o/{Uri.EscapeDataString(objectName)}?alt=media";

        return (uploadUrl, downloadUrl);
    }
}
