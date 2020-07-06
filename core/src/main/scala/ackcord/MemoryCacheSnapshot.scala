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

import java.time.temporal.ChronoUnit
import java.time.{Instant, OffsetDateTime}

import ackcord.CacheSnapshot.BotUser
import ackcord.cachehandlers.CacheSnapshotBuilder
import ackcord.data._
import ackcord.util.CoreCompat
import shapeless.tag.@@

/**
  * Represents the cache at some point in time
  */
case class MemoryCacheSnapshot(
    seq: Long,
    botUser: User @@ BotUser,
    dmChannelMap: SnowflakeMap[DMChannel, DMChannel],
    groupDmChannelMap: SnowflakeMap[GroupDMChannel, GroupDMChannel],
    unavailableGuildMap: SnowflakeMap[Guild, UnavailableGuild],
    guildMap: SnowflakeMap[Guild, Guild],
    messageMap: SnowflakeMap[TextChannel, SnowflakeMap[Message, Message]],
    lastTypedMap: SnowflakeMap[TextChannel, SnowflakeMap[User, Instant]],
    userMap: SnowflakeMap[User, User],
    banMap: SnowflakeMap[Guild, SnowflakeMap[User, Ban]],
    processor: MemoryCacheSnapshot.CacheProcessor
) extends CacheSnapshotWithMaps {

  override type MapType[K, V] = SnowflakeMap[K, V]

  override def getChannelMessages(channelId: TextChannelId): SnowflakeMap[Message, Message] =
    messageMap.getOrElse(channelId, SnowflakeMap.empty)

  override def getChannelLastTyped(channelId: TextChannelId): SnowflakeMap[User, Instant] =
    lastTypedMap.getOrElse(channelId, SnowflakeMap.empty)

  override def getGuildBans(id: GuildId): SnowflakeMap[User, Ban] =
    banMap.getOrElse(id, SnowflakeMap.empty)
}
object MemoryCacheSnapshot {

  /**
    * An action taken every time a cache is built.
    */
  trait CacheProcessor {

    /**
      * Process the current cache snapshot builder
      * @param current The current processor being ran
      * @param builder The builder being worked on
      * @return The processor to be used for the next update
      */
    def apply(current: CacheProcessor, builder: CacheSnapshotBuilder): CacheProcessor

    def safeApply(current: CacheProcessor, next: CacheProcessor, builder: CacheSnapshotBuilder): CacheProcessor =
      if (this eq current) next
      else current(next, builder)
  }

  lazy val defaultCacheProcessor: CacheProcessor = everyN(10, 10, cleanGarbage(30, 5))

  /**
    * A cache processor that will execute another processor every N cache updates.
    *
    * @param every How often the processor will run
    * @param remaining How many updates until the processor is run
    */
  //noinspection ConvertExpressionToSAM
  def everyN(every: Int, remaining: Int, doAction: CacheProcessor): CacheProcessor = new CacheProcessor {
    override def apply(processor: CacheProcessor, builder: CacheSnapshotBuilder): CacheProcessor =
      if (remaining > 0)
        safeApply(processor, everyN(every, remaining - 1, doAction), builder)
      else
        safeApply(processor, doAction(everyN(every, every, doAction), builder), builder)
  }

  /**
    * A cache processor that will clean out typical garbage older that a
    * given time.
    * @param keepMessagesFor How long messages should be kept for
    * @param keepTypedFor How long typed notifications should be kept for
    */
  def cleanGarbage(keepMessagesFor: Int, keepTypedFor: Int): CacheProcessor = (processor, builder) => {
    val messagesCleanThreshold = OffsetDateTime.now().minusMinutes(keepMessagesFor)
    val typedCleanThreshold    = Instant.now().minus(keepTypedFor, ChronoUnit.MINUTES)

    builder.messageMap = builder.messageMap.modifyOrRemove { (_, messageMap) =>
      val newMap = messageMap.modifyOrRemove { (_, m) =>
        Option.when(m.editedTimestamp.getOrElse(m.timestamp).isAfter(messagesCleanThreshold))(m)
      }

      Option.when(newMap.nonEmpty)(newMap)
    }

    builder.lastTypedMap = builder.lastTypedMap.modifyOrRemove { (_, typedMap) =>
      val newMap = typedMap.modifyOrRemove { (_, i) =>
        Option.when(i.isAfter(typedCleanThreshold))(i)
      }

      Option.when(newMap.nonEmpty)(newMap)
    }

    processor
  }
}
