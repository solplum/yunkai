package com.wangzan101.mika.core.run

import com.wangzan101.mika.core.action.ConfirmChoice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelOutboxTest {
    private val peer = ChannelPeer("fake", "owner-peer", "thread")

    private class FakeChannel(override val confirms: Boolean = true) : Channel {
        override val id = "fake"
        val sent = mutableListOf<Delivery>()
        var outcome = SendOutcome.Delivered
        var alive = true
        private var sink: ChannelInputSink? = null
        override fun isAlive(peer: ChannelPeer) = alive
        override fun start(sink: ChannelInputSink): AutoCloseable { this.sink = sink; return AutoCloseable { this.sink = null } }
        override suspend fun send(peer: ChannelPeer, delivery: Delivery): SendOutcome { sent += delivery; return outcome }
        suspend fun receive(input: ChannelInput) { sink?.receive(input) }
    }

    @Test fun askReplyConfirmAndResultRoundTrip() = runBlocking {
        val channel = FakeChannel()
        val outbox = Outbox()
        val owners = MemoryOwnerIdentity().also { it.bind(peer) }
        var active: Delivery.Ask? = null
        val received = mutableListOf<String>()
        val gateway = ChannelGateway(owners, { active }, object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) { received += "new:$text" }
            override suspend fun answer(threadId: String, askId: String, text: String): Boolean { received += "answer:$text"; active = null; return true }
            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice): Boolean { received += "confirm:$choice"; active = null; return true }
            override suspend fun stop(runId: RunId) { received += "stop" }
            override suspend fun cancelAsk(threadId: String) { received += "cancelAsk"; active = null }
        })
        gateway.start(channel)
        val input = Delivery.Ask("d1", "thread", "a1", Delivery.Ask.Kind.Input, "which?")
        active = input
        assertEquals(DeliveryState.Delivered, outbox.deliver(channel, peer, input).state)
        channel.receive(ChannelInput.Text(peer, "m1", "A", replyToAskId = "a1"))
        val confirm = Delivery.Ask("d2", "thread", "a2", Delivery.Ask.Kind.Confirm, "order?")
        active = confirm
        outbox.deliver(channel, peer, confirm)
        channel.receive(ChannelInput.Confirm(peer, "a2", ConfirmChoice.Once))
        outbox.deliver(channel, peer, Delivery.Result("d3", "thread", "done"))
        assertEquals(listOf("answer:A", "confirm:Once"), received)
        assertEquals(listOf(input, confirm, Delivery.Result("d3", "thread", "done")), channel.sent)
        assertEquals(DeliveryState.Delivered, outbox.deliver(channel, peer, input).state)
        assertEquals(3, channel.sent.size)
    }

    @Test fun ownerAndChannelRulesBlockUntrustedApproval() = runBlocking {
        val channel = FakeChannel(confirms = false)
        val payment = Delivery.Ask("d", "thread", "a", Delivery.Ask.Kind.Confirm, "payment?")
        val events = mutableListOf<String>()
        val gateway = ChannelGateway(MemoryOwnerIdentity().also { it.bind(peer) }, { payment }, object : ChannelGateway.Route {
            override suspend fun newTask(peer: ChannelPeer, messageId: String, text: String) { events += "new" }
            override suspend fun answer(threadId: String, askId: String, text: String) = true
            override suspend fun confirm(threadId: String, askId: String, choice: ConfirmChoice): Boolean { events += "confirm"; return true }
            override suspend fun stop(runId: RunId) = Unit
            override suspend fun cancelAsk(threadId: String) = Unit
        })
        gateway.start(channel)
        channel.receive(ChannelInput.Confirm(peer, "a", ConfirmChoice.Once))
        channel.receive(ChannelInput.Confirm(peer.copy(peerId = "stranger"), "a", ConfirmChoice.Once))
        assertEquals(emptyList<String>(), events)
    }

    @Test fun uncertainSendIsNeverBlindlyRetried() = runBlocking {
        val channel = FakeChannel().apply { outcome = SendOutcome.Uncertain }
        val outbox = Outbox()
        val result = Delivery.Result("one", "thread", "done")
        assertEquals(DeliveryState.Uncertain, outbox.deliver(channel, peer, result).state)
        assertEquals(DeliveryState.Uncertain, outbox.deliver(channel, peer, result).state)
        assertEquals(1, channel.sent.size)
    }

    @Test fun deadSessionIsNotSentTo() = runBlocking {
        val channel = FakeChannel().apply { alive = false }
        val record = Outbox().deliver(channel, peer, Delivery.Notice("n1", "thread", "hello"))
        assertEquals(DeliveryState.Failed, record.state)
        assertEquals(emptyList<Delivery>(), channel.sent)
    }
}
