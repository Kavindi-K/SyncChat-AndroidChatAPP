using System.Collections.Generic;
using System.Threading.Tasks;
using FluentValidation;
using SyncChat.Application.Interfaces;
using SyncChat.Application.Models;

namespace SyncChat.Application.UseCases.Users;

public class UserSearchUseCase
{
    private readonly IUserRepository _userRepository;
    private readonly IValidator<string> _validator;

    public UserSearchUseCase(IUserRepository userRepository)
    {
        _userRepository = userRepository;
        _validator = new InlineValidator<string> {
            rules => rules.RuleFor(q => q)
                .NotEmpty().WithMessage("Search query cannot be empty")
                .MinimumLength(2).WithMessage("Search query must be at least 2 characters")
        };
    }

    public async Task<List<UserProfile>> ExecuteAsync(string query)
    {
        var validationResult = await _validator.ValidateAsync(query);
        if (!validationResult.IsValid)
        {
            throw new ValidationException(validationResult.Errors);
        }

        return await _userRepository.SearchUsersAsync(query);
    }
}
