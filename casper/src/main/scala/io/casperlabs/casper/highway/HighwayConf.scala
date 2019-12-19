package io.casperlabs.casper.highway

import java.util.concurrent.TimeUnit
import java.time.{Instant, LocalDateTime, ZoneId}
import scala.concurrent.duration._

final case class HighwayConf(
    /** Real-world time unit for one tick. */
    tickUnit: TimeUnit,
    /** Starting tick for the genesis era. */
    genesisEraStartTick: Instant,
    /** Method for calculating the end of the era based on the start tick. */
    eraDuration: HighwayConf.EraDuration,
    /** Amount of time to go back before the start of the era for picking the booking block. */
    bookingTicks: FiniteDuration,
    /** Amount of time to wait after the booking before we pick the key block, collecting the magic bits along the way. */
    entropyTicks: FiniteDuration,
    /** Stopping condition for producing ballots after the end of the era. */
    postEraVotingDuration: HighwayConf.VotingDuration
) {
  import HighwayConf._

  private val NanosPerSec = 1000000000L

  /** Time to go back before the start of the era for picking the key block. */
  def keyTicks: FiniteDuration =
    bookingTicks minus entropyTicks

  /** Convert a `timeUnit` specific (it's up to the caller to make sure this is compatible)
    * number of ticks since the Unix epch to an instant in time.
    */
  def toInstant(t: Ticks): Instant = {
    val n = tickUnit.toNanos(t)
    val s = tickUnit.toSeconds(t)
    Instant.ofEpochSecond(s, n - s * NanosPerSec)
  }

  /** Convert an instant in time to the number of ticks we can store in the era model,
    * or assign to a round ID in a block.
    */
  def toTicks(t: Instant): Ticks =
    Ticks(tickUnit.convert(t.getEpochSecond * NanosPerSec + t.getNano.toLong, TimeUnit.NANOSECONDS))

  private def eraEndTick(startTick: Instant, duration: EraDuration): Instant = {
    import EraDuration.CalendarUnit._
    val UTC = ZoneId.of("UTC")

    duration match {
      case EraDuration.FixedLength(ticks) =>
        startTick plus ticks

      case EraDuration.Calendar(length, unit) =>
        val s = LocalDateTime.ofInstant(startTick, UTC)
        val e = unit match {
          case SECONDS => s.plusSeconds(length)
          case MINUTES => s.plusMinutes(length)
          case HOURS   => s.plusHours(length)
          case DAYS    => s.plusDays(length)
          case WEEKS   => s.plusWeeks(length)
          case MONTHS  => s.plusMonths(length)
          case YEARS   => s.plusYears(length)
        }
        e.atZone(UTC).toInstant
    }
  }

  /** Calculate the era end tick based on a start tick. */
  def eraEndTick(startTick: Instant): Instant =
    eraEndTick(startTick, eraDuration)

  /** The booking block is picked from a previous era, e.g. with 7 day eras
    * we look for the booking block 10 days before the era start, so there's
    * an extra era before the one with the booking block and the one where
    * that block becomes effective. This gives humans a week to correct any
    * problems in case there's no unique key block and booking block to use.
    *
    * However the second era, the one following genesis, won't have one before
    * genesis to look at, so the genesis era has to be longer to produce many
    * booking blocks, one for era 2, and one for era 3.
    */
  def genesisEraEndTick: Instant = {
    val endTick    = eraEndTick(genesisEraStartTick)
    val length     = endTick.toEpochMilli - genesisEraStartTick.toEpochMilli
    val multiplier = 1 + bookingTicks.toMillis / length
    (1 until multiplier.toInt).foldLeft(endTick)((t, _) => eraEndTick(t))
  }

  /** Any time we create a block it may have to be a booking block,
    * in which case we have to execute the auction. There will be
    * exactly one booking boundary per era, except in the genesis
    * which has more, so that multiple following eras can find
    * booking blocks in it.
    * For example era 2 will use 1a, and era 3 will use 1b:
    *   1a     1b     2      3
    * | .      .   |  .   |  .   |
    * The function returns the list of ticks that are a certain delay
    * back from the start of a upcoming era.
    */
  def criticalBoundaries(
      startTick: Instant,
      endTick: Instant,
      delayTicks: FiniteDuration
  ): List[Instant] = {
    def loop(acc: List[Instant], nextStartTick: Instant): List[Instant] = {
      val boundary = nextStartTick minus delayTicks
      if (boundary isBefore startTick) loop(acc, eraEndTick(nextStartTick))
      else if (boundary isBefore endTick) loop(boundary :: acc, eraEndTick(nextStartTick))
      else acc
    }
    loop(Nil, endTick).reverse
  }
}

object HighwayConf {

  /** By default we want eras to start on Sunday Midnight.
    * We want to be able to calculate the end of an era when we start it.
    */
  sealed trait EraDuration
  object EraDuration {

    /** Fixed length eras are easy to deal with, but over the years can accumulate leap seconds
      * which menas they will eventually move away from starting at the desired midnight.
      * In practice this shouldn't be a problem with the Java time library because it spreads
      * leap seconds throughout the day so that they appear to be exactly 86400 seconds.
      */
    case class FixedLength(ticks: FiniteDuration) extends EraDuration

    /** Fixed endings can be calculated with the calendar, to make eras take exactly one week (or a month),
      * but it means eras might have different lengths. Using this might mean that a different platform
      * which handles leap seconds differently could assign different tick IDs.
      */
    case class Calendar(length: Long, unit: CalendarUnit) extends EraDuration

    sealed trait CalendarUnit
    object CalendarUnit {
      case object SECONDS extends CalendarUnit
      case object MINUTES extends CalendarUnit
      case object HOURS   extends CalendarUnit
      case object DAYS    extends CalendarUnit
      case object WEEKS   extends CalendarUnit
      case object MONTHS  extends CalendarUnit
      case object YEARS   extends CalendarUnit
    }
  }

  /** Describe how long after the end of the era validator need to keep producing ballots to finalize the last few blocks. */
  sealed trait VotingDuration
  object VotingDuration {

    /** Produce ballots according to the leader schedule for up to a certain number of ticks, e.g. 2 days. */
    case class FixedLength(ticks: FiniteDuration) extends VotingDuration

    /** Produce ballots until a certain level of summits are achieved on top of the switch blocks. */
    case class SummitLevel(k: Int) extends VotingDuration
  }
}
