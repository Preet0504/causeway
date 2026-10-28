package causeway.mini

/** Small helpers over ujson so call sites stay readable. */
object Json:
  def str(o: Option[String]): ujson.Value = o.fold[ujson.Value](ujson.Null)(ujson.Str(_))
  def num(o: Option[Int]): ujson.Value    = o.fold[ujson.Value](ujson.Null)(ujson.Num(_))
  def dbl(o: Option[Double]): ujson.Value = o.fold[ujson.Value](ujson.Null)(ujson.Num(_))
  def arr(items: Iterable[ujson.Value]): ujson.Arr = ujson.Arr.from(items)
  def pretty(v: ujson.Value): String = ujson.write(v, indent = 2)

  extension (v: ujson.Value)
    /** Field of an object, treating JSON null and a missing key alike. */
    def opt(key: String): Option[ujson.Value] =
      v.objOpt.flatMap(_.get(key)).filterNot(_.isNull)
    def text(key: String): Option[String] = opt(key).collect { case ujson.Str(s) => s }
    def intOr(key: String, default: Int): Int =
      opt(key).collect { case ujson.Num(n) => n.toInt }.getOrElse(default)
    def boolOr(key: String, default: Boolean): Boolean =
      opt(key).collect { case ujson.Bool(b) => b }.getOrElse(default)
