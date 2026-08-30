using System.Threading.Channels;

namespace Kadans.SharedKernel.BackgroundTasks;

public interface IBackgroundTaskQueue
{
    void EnqueueBackgroundWorkItem(Func<IServiceProvider, CancellationToken, Task> workItem);
    ValueTask<Func<IServiceProvider, CancellationToken, Task>> Dequeue(
        CancellationToken cancellationToken
    );
}

public sealed class BackgroundTaskQueue : IBackgroundTaskQueue
{
    private readonly Channel<Func<IServiceProvider, CancellationToken, Task>> _queue =
        Channel.CreateBounded<Func<IServiceProvider, CancellationToken, Task>>(
            new BoundedChannelOptions(100) { FullMode = BoundedChannelFullMode.Wait }
        );

    public void EnqueueBackgroundWorkItem(
        Func<IServiceProvider, CancellationToken, Task> workItem
    ) => _queue.Writer.TryWrite(workItem);

    public ValueTask<Func<IServiceProvider, CancellationToken, Task>> Dequeue(
        CancellationToken cancellationToken
    ) => _queue.Reader.ReadAsync(cancellationToken);
}
