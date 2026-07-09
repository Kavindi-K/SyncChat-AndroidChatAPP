using System;
using System.Threading.Tasks;
using FluentValidation;
using SyncChat.Application.Interfaces;
using SyncChat.Application.Models;

namespace SyncChat.Application.UseCases.Messages;

public class SendMessageUseCase
{
    private readonly IMessageRepository _messageRepository;
    private readonly IConversationRepository _conversationRepository;
    private readonly IValidator<SendMessageInput> _validator;

    public SendMessageUseCase(
        IMessageRepository messageRepository,
        IConversationRepository conversationRepository)
    {
        _messageRepository = messageRepository;
        _conversationRepository = conversationRepository;
        _validator = new SendMessageInputValidator();
    }

    public async Task<Message> ExecuteAsync(SendMessageInput input)
    {
        var validationResult = await _validator.ValidateAsync(input);
        if (!validationResult.IsValid)
        {
            throw new ValidationException(validationResult.Errors);
        }

        // Verify conversation exists
        var conversation = await _conversationRepository.GetConversationByIdAsync(input.ConversationId);
        if (conversation == null)
        {
            throw new ValidationException("Conversation does not exist.");
        }

        // Verify sender is part of the conversation
        if (!conversation.ParticipantUids.Contains(input.SenderId))
        {
            throw new ValidationException("Sender is not a participant in this conversation.");
        }

        // Verify conversation is not blocked by either participant
        if (conversation.BlockedBy != null && conversation.BlockedBy.Length > 0)
        {
            throw new ValidationException("This conversation is blocked.");
        }

        var message = new Message
        {
            Id = Guid.NewGuid().ToString("N"),
            ConversationId = input.ConversationId,
            SenderId = input.SenderId,
            Text = input.Text,
            MediaUrl = input.MediaUrl,
            Timestamp = DateTime.UtcNow,
            ReadBy = new[] { input.SenderId }
        };

        // Save message
        var savedMessage = await _messageRepository.SaveMessageAsync(message);

        // Update last message in conversation
        var lastMsgInfo = new LastMessageInfo
        {
            Text = string.IsNullOrEmpty(input.Text) && !string.IsNullOrEmpty(input.MediaUrl) ? "[Image]" : input.Text,
            SenderId = input.SenderId,
            Timestamp = savedMessage.Timestamp
        };
        await _conversationRepository.UpdateLastMessageAsync(input.ConversationId, lastMsgInfo);

        return savedMessage;
    }
}

public record SendMessageInput(string ConversationId, string SenderId, string Text, string? MediaUrl);

public class SendMessageInputValidator : AbstractValidator<SendMessageInput>
{
    public SendMessageInputValidator()
    {
        RuleFor(x => x.ConversationId).NotEmpty().WithMessage("ConversationId is required.");
        RuleFor(x => x.SenderId).NotEmpty().WithMessage("SenderId is required.");
        
        // Either text or media URL must be provided
        RuleFor(x => x)
            .Must(x => !string.IsNullOrWhiteSpace(x.Text) || !string.IsNullOrWhiteSpace(x.MediaUrl))
            .WithMessage("Message text or media URL must be provided.");
            
        RuleFor(x => x.Text)
            .MaximumLength(1000).WithMessage("Message text cannot exceed 1000 characters.");
    }
}
