package co.pitzdev.bank.actors

import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import akka.util.Timeout
import co.pitzdev.bank.actors.PersistentBankAccount.Command.{CreateBankAccount, GetBankAccount}
import co.pitzdev.bank.actors.PersistentBankAccount.Response
import co.pitzdev.bank.actors.PersistentBankAccount.Response.GetBankAccountResponse

import java.util.UUID
import scala.concurrent.ExecutionContext

object Bank {
  //commands = messages

  import PersistentBankAccount.Command._
  import PersistentBankAccount.Response._
  import PersistentBankAccount.Command
  //Events
  sealed trait Event
  case class BankAccountCreated(id: String) extends Event

  //State
  case class State(accounts: Map[String, ActorRef[Command]])

  // command handler
  def commandHandler(context: ActorContext[Command]):(State, Command) => Effect[Event, State] = (state, command) =>
    command match {
      case createCommand @ CreateBankAccount(_, _, _, _) =>
        val id = UUID.randomUUID().toString
        val newBankAccount = context.spawn(PersistentBankAccount(id), id)
        Effect
        .persist(BankAccountCreated(id))
        .thenReply(newBankAccount)(_ => createCommand)
      case updateCommand @ UpdateBalance(id, _, _, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) =>
            Effect.reply(account)(updateCommand)
          case None =>
            Effect.reply(replyTo)(BankAccountBalanceUpdatedResponse(None)) // failed account search
        }

      case getBankAccountCommand @ GetBankAccount(id, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) =>
            Effect.reply(account)(getBankAccountCommand)
          case None =>
            Effect.reply(replyTo)(GetBankAccountResponse(None)) // failed account search
        }
    }
  //event handler
  def eventHandler(context: ActorContext[Command]): (State, Event) => State = (state, event) =>
    event match {
      case BankAccountCreated(id) =>
        val account = context.child(id) // Exist after Command Handler
          .getOrElse(context.spawn(PersistentBankAccount(id), id)) // does not exist in the recovery mode. so needs to be created
          .asInstanceOf[ActorRef[Command]]
        state.copy(state.accounts + (id -> account))
    }

  // behavior
  def apply(): Behavior[Command] =Behaviors.setup {context =>
    EventSourcedBehavior[Command, Event, State](
      persistenceId = PersistenceId.ofUniqueId("bank"),
      emptyState = State(Map()),
      commandHandler= commandHandler(context),
      eventHandler = eventHandler(context)
    )
  }
}

object BankPlayground {

  def main(args: Array[String]) : Unit ={
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
      val bank = context.spawn(Bank(), "bank")
      val logger = context.log

      val responseHandler = context.spawn(Behaviors.receiveMessage[Response] {
        case Response.BankAccountCreatedResponse(id) =>
          logger.info(s"Successfully Created Bank account $id")
          Behaviors.same
        case GetBankAccountResponse(mayBeBankAccount) =>
          logger.info(s"Account details $mayBeBankAccount")
          Behaviors.same
      }, "replyHandler")
      import scala.concurrent.duration._
      implicit val timeout: Timeout = Timeout(2.seconds)
      implicit val scheduler: Scheduler = context.system.scheduler
      implicit val ec: ExecutionContext = context.executionContext


      // bank ! CreateBankAccount("Peter Ndirangu", "USD", 10000, responseHandler)
      // bank ! GetBankAccount("d520fdf9-7355-4006-8cc0-864d53b9b319", responseHandler)
      Behaviors.empty
    }
    val system = ActorSystem(rootBehavior, "BankDemo")
  }
}
