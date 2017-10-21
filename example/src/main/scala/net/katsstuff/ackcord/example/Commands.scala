/*
 * This file is part of AkkaCord, licensed under the MIT License (MIT).
 *
 * Copyright (c) 2017 Katrix
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
package net.katsstuff.ackcord.example

import java.nio.file.Paths

import akka.NotUsed
import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import net.katsstuff.ackcord.DiscordClient.{ClientActor, ShutdownClient}
import net.katsstuff.ackcord.commands.CommandDispatcher.{Command, NoCommand, UnknownCommand}
import net.katsstuff.ackcord.commands.CommandParser.{ParseError, ParsedCommand}
import net.katsstuff.ackcord.data._
import net.katsstuff.ackcord.example.InfoChannelCommand.GetChannelInfo
import net.katsstuff.ackcord.http.rest.Requests
import net.katsstuff.ackcord.http.rest.Requests.CreateMessageData
import net.katsstuff.ackcord.syntax._
import net.katsstuff.ackcord.{Request, RequestResponse}

class PingCommand(client: ClientActor) extends Actor {
  override def receive: Receive = {
    case Command(msg, _, c) =>
      implicit val cache: CacheSnapshot = c
      //Construct request manually
      client ! Request(
        Requests.CreateMessage(msg.channelId, CreateMessageData("Pong", None, tts = false, None, None)),
        NotUsed,
        None
      )
  }
}
object PingCommand {
  def props(client: ClientActor): Props = Props(new PingCommand(client))
}

class SendFileCommand(client: ClientActor) extends Actor {
  override def receive: Receive = {
    case Command(msg, _, c) =>
      implicit val cache: CacheSnapshot = c
      val embed = OutgoingEmbed(
        title = Some("This is an embed"),
        description = Some("This embed is sent together with a file"),
        fields = Seq(EmbedField("FileName", "theFile.txt"))
      )

      msg.tChannel.foreach { tChannel =>
        //Use channel to construct request
        client ! tChannel.sendMessage(
          "Here is the file",
          file = Some(Paths.get("theFile.txt")),
          embed = Some(embed)
        )
      }
  }
}
object SendFileCommand {
  def props(client: ClientActor): Props = Props(new SendFileCommand(client))
}

class InfoChannelCommand(client: ClientActor) extends Actor {

  val infoResponseHandler: ActorRef = context.actorOf(InfoCommandHandler.props(client))

  override def receive: Receive = {
    case ParsedCommand(msg, channel: GuildChannel, _, c) =>
      implicit val cache: CacheSnapshot = c
      client ! client.fetchChannel(
        channel.id,
        GetChannelInfo(channel.guildId, channel.id, msg.channelId, c),
        Some(infoResponseHandler)
      )
    case ParseError(msg, error, c) =>
      implicit val cache: CacheSnapshot = c
      msg.tChannel.foreach(client ! _.sendMessage(error))
  }
}
object InfoChannelCommand {
  def props(client: ClientActor): Props = Props(new InfoChannelCommand(client))
  case class GetChannelInfo(
      guildId: GuildId,
      requestedChannelId: ChannelId,
      senderChannelId: ChannelId,
      c: CacheSnapshot
  )
}

class InfoCommandHandler(client: ClientActor) extends Actor with ActorLogging {
  override def receive: Receive = {
    case RequestResponse(res, GetChannelInfo(guildId, requestedChannelId, senderChannelId, c)) =>
      implicit val cache: CacheSnapshot = c
      val optName = cache.getGuildChannel(guildId, requestedChannelId).map(_.name)
      optName match {
        case Some(name) =>
          cache.getGuildChannel(guildId, senderChannelId) match {
            case Some(channel: TGuildChannel) => client ! channel.sendMessage(s"Info for $name:\n$res")
            case Some(channel: GuildChannel)  => log.warning("{} is not a valid text channel", channel.name)
            case None                         => log.warning("No channel found for {}", requestedChannelId)
          }
        case None => log.warning("No channel found for {}", requestedChannelId)
      }

      log.info(res.toString)
  }
}
object InfoCommandHandler {
  def props(client: ClientActor): Props = Props(new InfoCommandHandler(client))
}

class KillCommand(client: ClientActor) extends Actor with ActorLogging {
  override def receive: Receive = {
    case Command(_, _, _) =>
      log.info("Received shutdown command")
      client ! ShutdownClient
  }
}
object KillCommand {
  def props(client: ClientActor): Props = Props(new KillCommand(client))
}

class CommandErrorHandler(client: ClientActor) extends Actor {
  override def receive: Receive = {
    case NoCommand(msg, c) =>
      implicit val cache: CacheSnapshot = c
      msg.tChannel.foreach(client ! _.sendMessage(s"No command specified."))
    case UnknownCommand(msg, args, c) =>
      implicit val cache: CacheSnapshot = c
      msg.tChannel.foreach(client ! _.sendMessage(s"No command named ${args.head} known"))
  }
}
object CommandErrorHandler {
  def props(client: ClientActor): Props = Props(new CommandErrorHandler(client))
}