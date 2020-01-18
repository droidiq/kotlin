// "Remove single lambda parameter declaration" "false"
// ACTION: Add braces to 'when' entry
// ACTION: Remove explicit lambda parameter types (may break code)
// ACTION: Rename to _
fun test(i: Int) {
    val p: (String) -> Boolean =
        when (i) {
            1 -> { <caret>s: String -> true }
            else -> { s: String -> false }
        }
}