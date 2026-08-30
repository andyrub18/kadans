namespace Kadans.Api.Models;

public sealed class TodoOccurrence
{
    public Guid Id { get; init; } = Guid.CreateVersion7();
    public Guid TodoId { get; set; }
    public DateTimeOffset OccurrenceDate { get; set; }
    public bool IsCancelled { get; set; }
    public string CancellationReason { get; set; } = string.Empty;

    public bool IsCompleted { get; set; }
    public DateTimeOffset? CompletedAt { get; set; }

    public Todo? Todo { get; set; }
    public string Remarks { get; set; } = string.Empty;
}
