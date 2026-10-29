package ovh.battistella.ondes.data.remote

data class PodcastTheme(
    val genreId: Int,
    val label: String,
)

object PodcastThemes {
    val all: List<PodcastTheme> = listOf(
        PodcastTheme(1318, "Technology"),
        PodcastTheme(1321, "Business"),
        PodcastTheme(1489, "News"),
        PodcastTheme(1488, "True Crime"),
        PodcastTheme(1303, "Comedy"),
        PodcastTheme(1533, "Science"),
        PodcastTheme(1512, "Health"),
        PodcastTheme(1487, "History"),
        PodcastTheme(1545, "Sports"),
        PodcastTheme(1304, "Education"),
        PodcastTheme(1310, "Music"),
        PodcastTheme(1324, "Society"),
        PodcastTheme(1301, "Arts"),
        PodcastTheme(1309, "TV & Film"),
    )
}
