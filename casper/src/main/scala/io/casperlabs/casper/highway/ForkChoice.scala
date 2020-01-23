package io.casperlabs.casper.highway

import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS}
import io.casperlabs.models.Message
import io.casperlabs.storage.BlockHash
import simulacrum.typeclass

/** Some sort of stateful, memoizing implementation of a fast fork choice.
  * Should have access to the era tree to check what are the latest messages
  * along the path, who are the validators in each era, etc.
  */
@typeclass
trait ForkChoice[F[_]] {

  /** Execute the fork choice based on a key block of an era:
    * - go from the key block to the switch block of the era, using the validators in that era
    * - go from the switch block using the next era's validators to the end of the next era
    * - repeat until the we arrive at the tips
    * - return the fork choice block along with all the justifications taken into account.
    *
    * `keyBlockHash` is the identifier of the era in which we are seeking the
    * fork choice. The key block itself will have an era ID, which the implementation
    * can use to consult the `DagStorage` to find out the latest messages in that era.
    *
    * All the switch blocks based on the same keyblock lead to the same child era.
    * At some point the algorithm can find which switch block is the fork choice,
    * and carry on towards the latest messages in the era corresponding to `keyBlockHash`,
    * but stop there, without recursing into potential child eras.
    */
  def fromKeyBlock(keyBlockHash: BlockHash): F[ForkChoice.Result]

  /** Calculate the fork choice from a set of known blocks. This can be used
    * either to validate the main parent of an incoming block, or to pick a
    * target for a lambda response, given the lambda message and the validator's
    * own latest message as justifications.
    *
    * `keyBlockHash` is passed because in order to be able to validate that a
    * fork choice is correct we need to know what era it started the evaluation
    * from; for example if we don't have a block in the child era yet, just
    * the ballots that vote on the switch block in the parent era, we have to
    * know that the fork choice was run from the grandparent or the great-
    * grandparent era, and the parent-era justifications on their own don't
    * indicate this.
    */
  def fromJustifications(
      keyBlockHash: BlockHash,
      justifications: Set[BlockHash]
  ): F[ForkChoice.Result]
}
object ForkChoice {
  case class Result(
      block: Message.Block,
      // The fork choice must take into account messages from the parent
      // era's voting period as well, in order to be able to tell which
      // switch block in the end of the era to build on, and so which
      // blocks in the child era to follow. The new block we build
      // on top of the main parent can cite all these justifications.
      justifications: Set[Message]
  ) {
    def justificationsMap: Map[PublicKeyBS, Set[BlockHash]] =
      justifications.toSeq
        .map(j => PublicKey(j.validatorId) -> j.messageHash)
        .groupBy(_._1)
        .mapValues(_.map(_._2).toSet)
  }
}

/** A component which can be notified when an era higher up in the tree has a new message. */
@typeclass
trait ForkChoiceManager[F[_]] extends ForkChoice[F] {

  /** Tell the fork choice that deals with a given era that an ancestor era has a new message. */
  def updateLatestMessage(
      // The era in which we want to update the latest message.
      keyBlockHash: BlockHash,
      // The latest message in the ancestor era that must be taken into account from now.
      message: Message
  ): F[Unit]
}
