/*
 * This file is part of AckCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2019 Katrix
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package ackcord

import java.time.Instant

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

import ackcord.MemoryCacheSnapshot.CacheProcessor
import ackcord.cachehandlers.CacheTypeRegistry
import ackcord.data.PresenceStatus
import ackcord.data.raw.RawActivity
import ackcord.gateway.{GatewayEvent, GatewayIntents}
import ackcord.requests.Ratelimiter
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.OverflowStrategy
import akka.util.Timeout
import org.slf4j.Logger

/**
  * Settings used when connecting to Discord.
  * @param token The token for the bot.
  * @param largeThreshold The large threshold.
  * @param shardNum The shard index of this shard.
  * @param shardTotal The amount of shards.
  * @param idleSince If the bot has been idle, set the time since.
  * @param activity Send an activity when connecting.
  * @param status The status to use when connecting.
  * @param afk If the bot should be afk when connecting.
  * @param system The actor system to use.
  * @param requestSettings The request settings to use.
  */
case class ClientSettings(
    token: String,
    largeThreshold: Int = 50,
    shardNum: Int = 0,
    shardTotal: Int = 1,
    idleSince: Option[Instant] = None,
    @deprecatedName("gameStatus", since = "0.17") activity: Option[RawActivity] = None,
    status: PresenceStatus = PresenceStatus.Online,
    afk: Boolean = false,
    guildSubscriptions: Boolean = true,
    intents: GatewayIntents = GatewayIntents.AllWithoutPresences,
    system: ActorSystem[Nothing] = ActorSystem(Behaviors.ignore, "AckCord"),
    requestSettings: RequestSettings = RequestSettings(),
    cacheProcessor: CacheProcessor = MemoryCacheSnapshot.defaultCacheProcessor,
    cacheParallelism: Int = 4,
    cacheBufferSize: PubSubBufferSize = PubSubBufferSize(),
    gatewayEventsBufferSize: PubSubBufferSize = PubSubBufferSize(),
    ignoredEvents: Seq[Class[_ <: GatewayEvent[_]]] = Nil,
    cacheTypeRegistry: Logger => CacheTypeRegistry = CacheTypeRegistry.default
) {

  val gatewaySettings: GatewaySettings = GatewaySettings(
    token,
    largeThreshold,
    shardNum,
    shardTotal,
    idleSince,
    activity,
    status,
    afk,
    guildSubscriptions,
    intents
  )

  implicit val executionContext: ExecutionContext = system.executionContext

  /**
    * Create a [[DiscordClient]] from these settings.
    */
  def createClient(): Future[DiscordClient] = {
    implicit val actorSystem: ActorSystem[Nothing] = system

    DiscordShard.fetchWsGateway.flatMap { uri =>
      val cache = Cache.create(cacheProcessor, cacheParallelism, cacheBufferSize, gatewayEventsBufferSize)
      val clientActor = actorSystem.systemActorOf(
        DiscordClientActor(Seq(DiscordShard(uri, gatewaySettings, cache, ignoredEvents, cacheTypeRegistry)), cache),
        "DiscordClient"
      )

      implicit val timeout: Timeout = Timeout(1.second)
      clientActor.ask[DiscordClientActor.GetRatelimiterReply](DiscordClientActor.GetRatelimiter).map {
        case DiscordClientActor.GetRatelimiterReply(ratelimiter) =>
          val requests = requestSettings.toRequests(token, ratelimiter)

          new DiscordClientCore(
            cache,
            requests,
            clientActor
          )
      }
    }
  }

  /**
    * Create a [[DiscordClient]] from these settings while letting Discord
    * set the shard amount.
    */
  def createClientAutoShards(): Future[DiscordClient] = {
    implicit val actorSystem: ActorSystem[Nothing] = system

    DiscordShard.fetchWsGatewayWithShards(token).flatMap {
      case (uri, receivedShardTotal) =>
        val cache  = Cache.create()
        val shards = DiscordShard.many(uri, receivedShardTotal, gatewaySettings, cache, Nil, CacheTypeRegistry.default)

        val clientActor = actorSystem.systemActorOf(DiscordClientActor(shards, cache), "DiscordClient")

        implicit val timeout: Timeout = Timeout(1.second)
        clientActor.ask[DiscordClientActor.GetRatelimiterReply](DiscordClientActor.GetRatelimiter).map {
          case DiscordClientActor.GetRatelimiterReply(ratelimiter) =>
            val requests = requestSettings.toRequests(token, ratelimiter)

            new DiscordClientCore(
              cache,
              requests,
              clientActor
            )
        }
    }
  }
}

/**
  * @param parallelism Parallelism to use for requests.
  * @param bufferSize The buffer size to use for waiting requests.
  * @param maxRetryCount The maximum amount of times a request will be retried.
  *                      Only affects requests that uses retries.
  * @param overflowStrategy The overflow strategy to use when the buffer is full.
  * @param maxAllowedWait The max allowed wait time before giving up on a request.
  */
case class RequestSettings(
    millisecondPrecision: Boolean = true,
    relativeTime: Boolean = false,
    parallelism: Int = 4,
    bufferSize: Int = 32,
    maxRetryCount: Int = 3,
    overflowStrategy: OverflowStrategy = OverflowStrategy.backpressure,
    maxAllowedWait: FiniteDuration = 2.minutes
) {

  def toRequests(token: String, ratelimitActor: ActorRef[Ratelimiter.Command])(
      implicit system: ActorSystem[Nothing]
  ): Requests =
    new Requests(
      BotAuthentication(token),
      ratelimitActor,
      millisecondPrecision,
      relativeTime,
      parallelism,
      maxRetryCount,
      bufferSize,
      overflowStrategy,
      maxAllowedWait
    )
}
