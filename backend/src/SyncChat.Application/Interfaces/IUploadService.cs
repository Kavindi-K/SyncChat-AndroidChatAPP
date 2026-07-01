using System.Threading.Tasks;

namespace SyncChat.Application.Interfaces;

public interface IUploadService
{
    Task<(string UploadUrl, string DownloadUrl)> GenerateSignedUrlAsync(string userId, string fileName, string contentType);
}
