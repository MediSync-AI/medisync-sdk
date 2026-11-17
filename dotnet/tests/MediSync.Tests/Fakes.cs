using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;

namespace MediSync.Tests;

/// <summary>An HTTP handler that answers each request with the next scripted function.</summary>
public sealed class FakeHandler : HttpMessageHandler
{
    public readonly List<(HttpRequestMessage Request, string Body)> Seen = new();
    private readonly Queue<Func<HttpRequestMessage, Task<HttpResponseMessage>>> _answers = new();

    public FakeHandler Then(Func<HttpRequestMessage, Task<HttpResponseMessage>> answer)
    {
        _answers.Enqueue(answer);
        return this;
    }

    public FakeHandler Json(int status, string json, Action<HttpResponseMessage>? edit = null) => Then(_ =>
    {
        var res = new HttpResponseMessage((HttpStatusCode)status) { Content = new StringContent(json, Encoding.UTF8, "application/json") };
        edit?.Invoke(res);
        return Task.FromResult(res);
    });

    protected override async Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
    {
        var body = request.Content is null ? "" : await request.Content.ReadAsStringAsync(cancellationToken);
        lock (Seen) Seen.Add((request, body));
        if (_answers.Count == 0) throw new InvalidOperationException("no answer scripted");
        var answer = _answers.Dequeue();
        cancellationToken.ThrowIfCancellationRequested();
        return await answer(request).WaitAsync(cancellationToken);
    }
}
