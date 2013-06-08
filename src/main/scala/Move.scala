package scrabble

import scala.util.{ Try, Success, Failure }
import scalaz.NonEmptyList

abstract class Move(game: Game) {

  def makeMove: Try[Game]

  val meetsEndCondition: Boolean

}

case class PlaceLettersMove(game: Game, placed: NonEmptyList[(Pos, Tile)]) extends Move(game) {

  //@TODO:Think about how to record games. Tidy up buildWords function. Test it - properly.

  /** Processes the placed letters. Sorts them into positional order. */
  private lazy val placedProcessed = placed.list.sortBy { case (pos: Pos, _) => (pos.x, pos.y) }

  /**
   * Returns the updated game if the move is a valid scrabble move, otherwise returns an InvalidMove
   * with an explanation of why the move is invalid
   */
  def makeMove: Try[Game] = {

    if (!obeysFirstMovePositionRule) Failure(FirstMovePositionWrong()) else {
      if (!alreadyOccupiedSquares.isEmpty) Failure(SquareOccupiedClientError()) else {

        score.flatMap {
          scr =>
            placeLetters.map {
              case (board, player) =>
                // give the player letters
                val (given, newbag) = game.bag.remove(amountPlaced)
                val newplayer = player.copy(letters = player.letters ++ given, score = player.score + scr.overAllScore)
                val nextPlayer = game.nextPlayerNo
                val players = game.players.updated(game.playersMove, newplayer)
                game.copy(players = players, board = board, playersMove = nextPlayer, bag = newbag, moves = game.moves + 1,
                  consecutivePasses = 0)
            }
        }
      }
    }

  }

  /* @TODO: Think about whether we should determine whether a valid move is possible for any player from the position, 
   * or perhaps end on multiple consecutive passes */
  lazy val meetsEndCondition: Boolean = {

    makeMove match {
      case Success(newGame) => (newGame.bag.size == 0 && newGame.players.get(game.playersMove).get.letters.size == 0)
      case _ => false
    }
  }

  /** Removes letters from player's letter rack and updates the board. Returns an error if the player does not have the letters  */
  private lazy val placeLetters: Try[(Board, Player)] = {
    val currentPlayer = game.currentPlayer
    val playerLetters = currentPlayer.letters

    def place(placed: List[(Pos, Tile)], remainingLetters: List[Tile], board: Board): Try[(Board, Player)] = {
      placed match {
        case y :: rest =>
          /* Split the remaining player's letters up till it matches one of their placed letters. */
          val (upTo, after) = remainingLetters.span { let => let != y._2 }

          if (upTo.size == remainingLetters.size) Failure(playerDoesNotHaveLettersClientError()) else {
            val newLetters: List[Tile] = upTo ::: after.drop(1)
            place(rest, newLetters, board.placeLetter(y._1, y._2))
          }
        case Nil => Success(board, currentPlayer.replaceLetters(remainingLetters))
      }
    }
    place(placedProcessed, playerLetters, board)
  }

  /**
   * Returns either a list of lists of (Pos, Tile) which are the words (with position info preserved) formed by the
   *  placement of the letters or an error if the player has not placed the words linearly or the letters are not attached
   *   to at least one existing letter on the board
   */
  lazy val formedWords: Try[List[List[(Pos, Tile)]]] = buildWords

  /** Returns an overall score, and a score for each word. Returns a list of words that are not in the dictionary (empty if none) */
  lazy val score: Try[Score] = {
    def toWord(list: List[(Pos, Tile)]): String = list.map { case (pos, letter) => letter.letter }.mkString

    formedWords.flatMap {
      lists =>
        val (score, lsts, badwords) = lists.foldLeft((0, List.empty[(String, Int)], List.empty[String])) {
          case ((acc, lsts, badwords), xs) =>
            val word = toWord(xs)
            val bdwords = if (!game.dictionary.isValidWord(word)) word :: badwords else badwords
            val squares = xs.map { case (pos, let) => pos -> board.squareAt(pos).setLetter(let) }

            // Sort to put the word bonus squares last
            val (score, wordBonuses) = squares.foldLeft(0, List.empty[Int => Int]) {
              case ((scr, wordBonuses), (pos, sq)) =>
                val square = board.squareAt(pos)

                // If the bonus has already been used, ignore the bonus square
                board.squareAt(pos).tile.fold {
                  sq match {
                    case NormalSquare(Some(x)) => (scr + x.value, wordBonuses)
                    case DoubleLetterSquare(Some(x)) => (scr + (x.value * 2), wordBonuses)
                    case TripleLetterSquare(Some(x)) => (scr + (x.value * 3), wordBonuses)
                    case DoubleWordSquare(Some(x)) => (scr + x.value, ((i: Int) => i * 2) :: wordBonuses)
                    case TripleWordSquare(Some(x)) => (scr + x.value, ((i: Int) => i * 3) :: wordBonuses)
                  }
                }(tile => (scr + tile.value, wordBonuses))
            }

            val finalScore = wordBonuses.foldLeft(score) { case (score, func) => func(score) }

            (acc + finalScore, lsts :+ (word, finalScore), bdwords)

        }
        val the_score: Score = if (sevenLetterBonus) Score(score + 50, lsts) else Score(score, lsts)

        if (badwords.isEmpty) Success(the_score) else Failure(WordsNotInDictionary(badwords, the_score))

    }

  }

  private lazy val obeysFirstMovePositionRule = if (game.moves > 0) true else {
    placedProcessed.find { case (pos, let) => pos == startPosition } isDefined
  }

  private lazy val alreadyOccupiedSquares = placedProcessed.find { case (pos: Pos, letter: Tile) => !(board.squareAt(pos).isEmpty) }
  private lazy val startPosition = Pos.posAt(8, 8).get
  private lazy val sevenLetterBonus: Boolean = amountPlaced == 7
  private val board = game.board
  private lazy val first = placedProcessed(0)

  private lazy val amountPlaced = placedProcessed.size

  private lazy val (startx, endx) = (placedProcessed(0)._1.x, placedProcessed(amountPlaced - 1)._1.x)
  private lazy val (starty, endy) = (placedProcessed(0)._1.y, placedProcessed(amountPlaced - 1)._1.y)

  private lazy val (horizontal, vertical): (Boolean, Boolean) = {
    if (amountPlaced == 1) {
      val horizontal = !board.LettersLeft(first._1).isEmpty || !board.LettersRight(first._1).isEmpty
      val vertical = !board.LettersAbove(first._1).isEmpty || !board.LettersBelow(first._1).isEmpty

      (horizontal, vertical)
    } else (starty == endy, startx == endx)
  }

  // @TODO: Absolutely hurrendous looking. Need to tidy it up.
  private def buildWords: Try[List[List[(Pos, Tile)]]] = {

    def isLastPlaced(pos: Pos): Boolean = pos.x == endx && pos.y == endy

    def afterEnd(pos: Pos) =
      if ((pos.x, pos.y) == (endx, endy)) {
        if (horizontal) board.LettersRight(pos) else board.LettersAbove(pos)
      } else Nil

    /** Returns words that are formed from the placement of a letter on a square on the board */
    def allAdjacentTo(pos: Pos, let: Tile): List[(Pos, Tile)] = {
      lazy val above = board.LettersAbove(pos)
      lazy val below = board.LettersBelow(pos)
      lazy val left = board.LettersLeft(pos)
      lazy val right = board.LettersRight(pos)

      if (horizontal) {
        if (!above.isEmpty || !below.isEmpty) {
          below ::: pos -> let :: above
        } else Nil
      } else {
        if (!left.isEmpty || !right.isEmpty) {
          left ::: pos -> let :: right
        } else Nil
      }
    }

    if (!horizontal && !vertical) Failure(NotLinear()) else {

      //@TODO: Tidy this up
      val startList: List[(Pos, Tile)] = ((if (horizontal) board.LettersLeft(placedProcessed(0)._1) else
        board.LettersBelow(placedProcessed(0)._1)) :+ (first._1, first._2)) ::: afterEnd(placedProcessed(0)._1)
      val otherWords = allAdjacentTo(first._1, first._2)
      val startWith: List[List[(Pos, Tile)]] = if (otherWords.isEmpty) List(startList) else List(startList) :+ otherWords

      /*@TODO: Replace this with a recursive function that returns Either[InvalidMove, ...] rather than break the fold with
       * a return
       */
      val lists: (Int, Int, List[List[(Pos, Tile)]]) = placedProcessed.tail.foldLeft(startx, starty, startWith) {
        case ((lastx, lasty, (x :: xs)), (pos: Pos, let)) =>
          val isLinear = if (horizontal) pos.y == lasty else pos.x == lastx
          if (!isLinear) return Failure(MisPlacedLetters(pos.x, pos.y))

          val comesAfter = if (horizontal) pos.x == lastx + 1 else pos.y == lasty + 1

          if (comesAfter) {
            // Add the letter to the first list
            val newlist: List[(Pos, Tile)] = x ::: pos -> let :: afterEnd(pos)
            val updatedList = newlist :: xs
            val otherWords = allAdjacentTo(pos, let)

            (pos.x, pos.y, if (!otherWords.isEmpty) updatedList :+ otherWords else updatedList)

          } else {

            val range = if (horizontal) List.range(lastx + 1, pos.x) else List.range(lasty + 1, pos.y)

            // Add the letters inbetween and the current char to the first list, then look for letters above and below the current char
            val between: List[(Pos, Tile)] = range.map {
              x =>
                val position = if (horizontal) Pos.posAt(x, pos.y) else Pos.posAt(pos.x, x)
                if (board.squareAt(position.get).isEmpty) return Failure(MisPlacedLetters(pos.x, pos.y))
                val sq = board.squareAt(position.get)
                position.get -> sq.tile.get
            }

            val newlist: List[(Pos, Tile)] = ((x ::: between)) ::: pos -> let :: afterEnd(pos)
            val updatedList = newlist :: xs
            val otherWords: List[(Pos, Tile)] = allAdjacentTo(pos, let)

            (pos.x, pos.y, if (!otherWords.isEmpty) updatedList :+ otherWords else updatedList)

          }

      }

      // If the placed letters extend a linear word, or are placed at right angles to another word (forming more words)
      lazy val isAttachedToWord = {
        println("attached: " + lists._3)
        lists._3(0).size > placedProcessed.size || lists._3.size > 1 || game.moves == 0
      }

      if (!isAttachedToWord) Failure(NotAttachedToWord()) else Success(lists._3)
    }
  }

}

case class PassMove(game: Game) extends Move(game) {
  def makeMove: Try[Game] = {
    Success(game.copy(consecutivePasses = game.consecutivePasses + 1, playersMove = game.nextPlayerNo,
      moves = game.moves + 1))
  }

  // Each player scoring 0 for three consecutive turns ends the game
  val meetsEndCondition: Boolean = game.consecutivePasses == game.players.size * 3
}

case class ExchangeMove(game: Game, exchangeLetters: List[Tile]) extends Move(game) {
  def makeMove: Try[Game] = ???

  val meetsEndCondition = false
}


