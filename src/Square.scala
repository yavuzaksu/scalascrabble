package scrabble;

abstract class Square {
  
  case class NormalSquare(tile: Option[Tile] = None ) extends Square
  
  case class DoubleLetterSquare(tile: Option[Tile] = None ) extends Square
  
  case class TripleLetterSquare(tile: Option[Tile] = None ) extends Square
  
  case class DoubleWordSquare(tile: Option[Tile] = None ) extends Square
  
  case class TripleWordSquare(tile: Option[Tile] = None ) extends Square
  
}