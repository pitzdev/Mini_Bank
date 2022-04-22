package co.pitzdev.bank.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
// a single bank Account
object PersistentBankAccount {
  /*
  - Fault tolerance
  -auditing
   */
  //commands = messages
  import  Command._
  import Response._
  sealed trait Command

  object Command {
    case class CreateBankAccount(user: String, currency: String, initialBalance: Double, replyTo: ActorRef[Response]) extends Command

    case class UpdateBalance(id: String, currency: String, amount: Double, replyTo: ActorRef[Response]) extends Command

    case class GetBankAccount(id: String, replyTo: ActorRef[Response]) extends Command
  }


  //events = to persist to cassandra
  trait Event

  case class BankAccountCreated(bankAccount: BankAccount) extends Event

  case class BalanceUpdated(amount: Double) extends Event

  //state
  case class BankAccount(id: String, user: String, currency: String, balance: Double)

  //responses
  sealed trait Response
 object Response {
   case class BankAccountCreatedResponse(id: String) extends Response

   case class BankAccountBalanceUpdatedResponse(bankAccount: Option[BankAccount]) extends Response

   case class GetBankAccountResponse(bankAccount: Option[BankAccount]) extends Response

 }


  //command handler = message handler => persist  and event
  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] = (state, command) =>
    command match {
      case CreateBankAccount(user, currency, initialBalance, bank) =>
        val id = state.id
        /*
        - bank created me
        - bank sends me CreateBankAccount
        - Persists BankAccountCreated
        - Reply back to bank with the BankAccountCreatedResponse
        - (The Bank surfaces the response to the http server
         */
        Effect
          .persist(BankAccountCreated(BankAccount(id, user, currency, initialBalance))) // persisted into database
          .thenReply(bank)(_ => BankAccountCreatedResponse(id))
      case UpdateBalance(_, _, amount, bank) =>
        val newBalance = state.balance + amount
        // check here for withdraw
        if (newBalance < 0)
          Effect.reply(bank)(BankAccountBalanceUpdatedResponse(None))
        else
          Effect
            .persist(BalanceUpdated(amount))
            .thenReply(bank)(newState => BankAccountBalanceUpdatedResponse(Some(newState)))
      case GetBankAccount(_, bank) =>
        Effect.reply(bank)(GetBankAccountResponse(Some(state)))
    }
  val eventHandler: (BankAccount, Event) => BankAccount = (state, event) =>
    event match {
      case BankAccountCreated(bankAccount) =>
        bankAccount
      case BalanceUpdated(amount) =>
        state.copy(balance = state.balance + amount)
    }


  //event handler => update state
  def apply(id: String): Behavior[Command] =
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0.0),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )


}

