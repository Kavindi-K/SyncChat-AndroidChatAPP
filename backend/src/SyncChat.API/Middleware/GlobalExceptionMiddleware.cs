using System.Net;
using System.Text.Json;
using FluentValidation;

namespace SyncChat.API.Middleware;

public class GlobalExceptionMiddleware
{
    private readonly RequestDelegate _next;
    private readonly ILogger<GlobalExceptionMiddleware> _logger;

    public GlobalExceptionMiddleware(RequestDelegate next, ILogger<GlobalExceptionMiddleware> logger)
    {
        _next = next;
        _logger = logger;
    }

    public async Task InvokeAsync(HttpContext context)
    {
        try
        {
            await _next(context);
        }
        catch (Exception ex)
        {
            _logger.LogError(ex, "An unhandled exception occurred during request execution.");
            await HandleExceptionAsync(context, ex);
        }
    }

    private static async Task HandleExceptionAsync(HttpContext context, Exception exception)
    {
        context.Response.ContentType = "application/json";

        var statusCode = HttpStatusCode.InternalServerError;
        object responseBody;

        if (exception is ValidationException validationException)
        {
            statusCode = HttpStatusCode.BadRequest;
            var errors = new Dictionary<string, string[]>();
            
            foreach (var error in validationException.Errors)
            {
                if (errors.ContainsKey(error.PropertyName))
                {
                    var existing = new List<string>(errors[error.PropertyName]) { error.ErrorMessage };
                    errors[error.PropertyName] = existing.ToArray();
                }
                else
                {
                    errors[error.PropertyName] = new[] { error.ErrorMessage };
                }
            }

            responseBody = new
            {
                Error = "Validation failed",
                Errors = errors,
                TraceId = context.TraceIdentifier
            };
        }
        else
        {
            responseBody = new
            {
                Error = exception.Message,
                TraceId = context.TraceIdentifier
            };
        }

        context.Response.StatusCode = (int)statusCode;
        var json = JsonSerializer.Serialize(responseBody);
        await context.Response.WriteAsync(json);
    }
}
