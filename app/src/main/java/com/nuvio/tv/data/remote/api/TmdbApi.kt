package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

interface TmdbApi {
    
    @GET("find/{external_id}")
    suspend fun findByExternalId(
        @Path("external_id") externalId: String,
        @Query("api_key") apiKey: String,
        @Query("external_source") externalSource: String = "imdb_id",
        @Query("language") language: String? = null
    ): Response<TmdbFindResponse>
    
    @GET("movie/{movie_id}/external_ids")
    suspend fun getMovieExternalIds(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbExternalIdsResponse>
    
    @GET("tv/{tv_id}/external_ids")
    suspend fun getTvExternalIds(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbExternalIdsResponse>

    @GET("movie/{movie_id}/videos")
    suspend fun getMovieVideos(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        /** Also list videos in these languages ("es,en,null"): every trailer, featurette, clip... */
        @Query("include_video_language") includeVideoLanguage: String? = null
    ): Response<TmdbVideosResponse>

    @GET("tv/{tv_id}/videos")
    suspend fun getTvVideos(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        /** Also list videos in these languages ("es,en,null"): every trailer, featurette, clip... */
        @Query("include_video_language") includeVideoLanguage: String? = null
    ): Response<TmdbVideosResponse>

    @GET("movie/{movie_id}")
    suspend fun getMovieDetails(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbDetailsResponse>

    @GET("tv/{tv_id}")
    suspend fun getTvDetails(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbDetailsResponse>

    /**
     * RCTV: a movie's titles, original language, countries and logos in one request (the title
     * cards of International Spotlight and Latin America & Spanish-Language Hits).
     */
    @GET("movie/{movie_id}")
    suspend fun getMovieTitleSpotlight(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        @Query("append_to_response") appendToResponse: String = "images",
        @Query("include_image_language") includeImageLanguage: String = "en,es"
    ): Response<TmdbTitleSpotlightResponse>

    /** The same for a series ([getMovieTitleSpotlight]). */
    @GET("tv/{tv_id}")
    suspend fun getTvTitleSpotlight(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String = "en-US",
        @Query("append_to_response") appendToResponse: String = "images",
        @Query("include_image_language") includeImageLanguage: String = "en,es"
    ): Response<TmdbTitleSpotlightResponse>

    @GET("movie/{movie_id}/credits")
    suspend fun getMovieCredits(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbCreditsResponse>

    @GET("tv/{tv_id}/credits")
    suspend fun getTvCredits(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbCreditsResponse>

    @GET("tv/{tv_id}/aggregate_credits")
    suspend fun getTvAggregateCredits(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbAggregateCreditsResponse>

    @GET("movie/{movie_id}/images")
    suspend fun getMovieImages(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("include_image_language") includeImageLanguage: String = "en,null"
    ): Response<TmdbImagesResponse>

    @GET("tv/{tv_id}/images")
    suspend fun getTvImages(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("include_image_language") includeImageLanguage: String = "en,null"
    ): Response<TmdbImagesResponse>

    @GET("movie/{movie_id}/release_dates")
    suspend fun getMovieReleaseDates(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbMovieReleaseDatesResponse>

    @GET("tv/{tv_id}/content_ratings")
    suspend fun getTvContentRatings(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbTvContentRatingsResponse>

    @GET("movie/{movie_id}/recommendations")
    suspend fun getMovieRecommendations(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1
    ): Response<TmdbRecommendationsResponse>

    @GET("tv/{tv_id}/recommendations")
    suspend fun getTvRecommendations(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1
    ): Response<TmdbRecommendationsResponse>

    @GET("collection/{collection_id}")
    suspend fun getCollectionDetails(
        @Path("collection_id") collectionId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbCollectionResponse>

    @GET("tv/{tv_id}/season/{season_number}")
    suspend fun getTvSeasonDetails(
        @Path("tv_id") tvId: Int,
        @Path("season_number") seasonNumber: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbSeasonResponse>

    @GET("person/{person_id}")
    suspend fun getPersonDetails(
        @Path("person_id") personId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbPersonResponse>

    @GET("person/{person_id}/combined_credits")
    suspend fun getPersonCombinedCredits(
        @Path("person_id") personId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbPersonCreditsResponse>

    @GET("company/{company_id}")
    suspend fun getCompanyDetails(
        @Path("company_id") companyId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbCompanyDetailsResponse>

    /** Streaming services (watch providers) TMDB knows for a region, with their logos. */
    @GET("watch/providers/movie")
    suspend fun getMovieWatchProviders(
        @Query("api_key") apiKey: String,
        @Query("watch_region") watchRegion: String? = null,
        @Query("language") language: String? = null
    ): Response<TmdbWatchProviderListResponse>

    /** RCTV: where a movie streams, by region (its "flatrate" services: Disney+, Netflix...). */
    @GET("movie/{movie_id}/watch/providers")
    suspend fun getMovieTitleWatchProviders(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbTitleWatchProvidersResponse>

    /** RCTV: where a show streams, by region. */
    @GET("tv/{tv_id}/watch/providers")
    suspend fun getTvTitleWatchProviders(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbTitleWatchProvidersResponse>

    @GET("watch/providers/tv")
    suspend fun getTvWatchProviders(
        @Query("api_key") apiKey: String,
        @Query("watch_region") watchRegion: String? = null,
        @Query("language") language: String? = null
    ): Response<TmdbWatchProviderListResponse>

    @GET("network/{network_id}")
    suspend fun getNetworkDetails(
        @Path("network_id") networkId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbNetworkDetailsResponse>

    @GET("discover/movie")
    suspend fun discoverMovies(
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String? = null,
        @Query("with_companies") withCompanies: String? = null,
        @Query("primary_release_date.lte") releaseDateLte: String? = null,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("with_genres") withGenres: String? = null,
        @Query("primary_release_date.gte") releaseDateGte: String? = null,
        @Query("vote_average.gte") voteAverageGte: Double? = null,
        @Query("vote_average.lte") voteAverageLte: Double? = null,
        @Query("with_original_language") withOriginalLanguage: String? = null,
        @Query("with_origin_country") withOriginCountry: String? = null,
        @Query("with_keywords") withKeywords: String? = null,
        /** People in the crew (director, writer...), TMDB person ids; "|" for any of them. */
        @Query("with_crew") withCrew: String? = null,
        /**
         * RCTV: a Year filter means the movie came out that year. TMDB's "year" also matches any later
         * release (a 2026 re-release of The Dark Knight), so its primary release year is asked for.
         */
        @Query("primary_release_year") year: Int? = null,
        @Query("watch_region") watchRegion: String? = null,
        @Query("with_watch_providers") withWatchProviders: String? = null,
        @Query("with_watch_monetization_types") withWatchMonetizationTypes: String? = null,
        @Query("without_companies") withoutCompanies: String? = null,
        @Query("without_genres") withoutGenres: String? = null,
        @Query("without_keywords") withoutKeywords: String? = null,
        @Query("without_watch_providers") withoutWatchProviders: String? = null,
        @Query("with_runtime.gte") withRuntimeGte: Int? = null,
        @Query("with_runtime.lte") withRuntimeLte: Int? = null,
        @Query("vote_count.lte") voteCountLte: Int? = null,
        /** Any release (with [withReleaseType], e.g. theatrical in [region]), not just the first one. */
        @Query("release_date.gte") anyReleaseDateGte: String? = null,
        @Query("release_date.lte") anyReleaseDateLte: String? = null,
        @Query("with_release_type") withReleaseType: String? = null,
        @Query("region") region: String? = null,
        /**
         * RCTV: TMDB leaves out titles it flags as "video" (shorts and specials released on video,
         * like Walt Disney Animation's) unless asked; its company and network pages count them, so
         * every list here includes them too (Walt Disney Animation Studios: 87, as on TMDB).
         */
        @Query("include_video") includeVideo: Boolean? = true
    ): Response<TmdbDiscoverResponse>

    /** Title search for movies. Results share the discover result shape. */
    @GET("search/movie")
    suspend fun searchMovies(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("language") language: String? = null,
        @Query("primary_release_year") primaryReleaseYear: Int? = null,
        @Query("page") page: Int = 1,
        @Query("include_adult") includeAdult: Boolean = false
    ): Response<TmdbDiscoverResponse>

    /** Title search for TV series. Results share the discover result shape. */
    @GET("search/tv")
    suspend fun searchTv(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("language") language: String? = null,
        @Query("first_air_date_year") firstAirDateYear: Int? = null,
        @Query("page") page: Int = 1,
        @Query("include_adult") includeAdult: Boolean = false
    ): Response<TmdbDiscoverResponse>

    @GET("discover/tv")
    suspend fun discoverTv(
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1,
        @Query("sort_by") sortBy: String? = null,
        @Query("with_companies") withCompanies: String? = null,
        @Query("with_networks") withNetworks: String? = null,
        @Query("first_air_date.lte") firstAirDateLte: String? = null,
        @Query("vote_count.gte") voteCountGte: Int? = null,
        @Query("with_genres") withGenres: String? = null,
        @Query("first_air_date.gte") firstAirDateGte: String? = null,
        @Query("vote_average.gte") voteAverageGte: Double? = null,
        @Query("vote_average.lte") voteAverageLte: Double? = null,
        @Query("with_original_language") withOriginalLanguage: String? = null,
        @Query("with_origin_country") withOriginCountry: String? = null,
        @Query("with_keywords") withKeywords: String? = null,
        @Query("first_air_date_year") firstAirDateYear: Int? = null,
        @Query("with_status") withStatus: String? = null,
        @Query("watch_region") watchRegion: String? = null,
        @Query("with_watch_providers") withWatchProviders: String? = null,
        @Query("with_watch_monetization_types") withWatchMonetizationTypes: String? = null,
        @Query("without_companies") withoutCompanies: String? = null,
        @Query("without_genres") withoutGenres: String? = null,
        @Query("without_keywords") withoutKeywords: String? = null,
        @Query("without_watch_providers") withoutWatchProviders: String? = null,
        @Query("vote_count.lte") voteCountLte: Int? = null,
        /** Series with an episode aired in these dates (new seasons and episodes). */
        @Query("air_date.gte") airDateGte: String? = null,
        @Query("air_date.lte") airDateLte: String? = null
    ): Response<TmdbDiscoverResponse>

    @GET("list/{list_id}")
    suspend fun getListDetails(
        @Path("list_id") listId: Int,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1
    ): Response<TmdbListDetailsResponse>

    @GET("search/company")
    suspend fun searchCompanies(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1
    ): Response<TmdbCompanySearchResponse>

    @GET("search/collection")
    suspend fun searchCollections(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1,
        @Query("include_adult") includeAdult: Boolean = false
    ): Response<TmdbCollectionSearchResponse>

    /** What's trending on TMDB ("movie" or "tv", over a "day" or a "week"). Same result shape as discover. */
    @GET("trending/{media_type}/{time_window}")
    suspend fun getTrending(
        @Path("media_type") mediaType: String,
        @Path("time_window") timeWindow: String,
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null,
        @Query("page") page: Int = 1
    ): Response<TmdbDiscoverResponse>

    @GET("genre/movie/list")
    suspend fun getMovieGenres(
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbGenresResponse>

    @GET("genre/tv/list")
    suspend fun getTvGenres(
        @Query("api_key") apiKey: String,
        @Query("language") language: String? = null
    ): Response<TmdbGenresResponse>

    @GET("search/keyword")
    suspend fun searchKeywords(
        @Query("api_key") apiKey: String,
        @Query("query") query: String,
        @Query("page") page: Int = 1
    ): Response<TmdbKeywordSearchResponse>

    @GET("movie/{movie_id}/alternative_titles")
    suspend fun getMovieAlternativeTitles(
        @Path("movie_id") movieId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbAlternativeTitlesResponse>

    @GET("tv/{tv_id}/alternative_titles")
    suspend fun getTvAlternativeTitles(
        @Path("tv_id") tvId: Int,
        @Query("api_key") apiKey: String
    ): Response<TmdbAlternativeTitlesResponse>
}

@JsonClass(generateAdapter = true)
data class TmdbAlternativeTitlesResponse(
    @Json(name = "titles") val movieTitles: List<TmdbAlternativeTitle>? = null,
    @Json(name = "results") val tvTitles: List<TmdbAlternativeTitle>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbAlternativeTitle(
    @Json(name = "iso_3166_1") val countryCode: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "type") val type: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbFindResponse(
    @Json(name = "movie_results") val movieResults: List<TmdbFindResult>? = null,
    @Json(name = "tv_results") val tvResults: List<TmdbFindResult>? = null,
    @Json(name = "tv_episode_results") val tvEpisodeResults: List<TmdbFindResult>? = null,
    @Json(name = "tv_season_results") val tvSeasonResults: List<TmdbFindResult>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbFindResult(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbExternalIdsResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "imdb_id") val imdbId: String? = null,
    @Json(name = "tvdb_id") val tvdbId: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbVideosResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "results") val results: List<TmdbVideoResult> = emptyList()
)

@JsonClass(generateAdapter = true)
data class TmdbVideoResult(
    @Json(name = "iso_639_1") val iso6391: String? = null,
    @Json(name = "iso_3166_1") val iso31661: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "key") val key: String? = null,
    @Json(name = "site") val site: String? = null,
    @Json(name = "size") val size: Int? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "official") val official: Boolean? = null,
    @Json(name = "published_at") val publishedAt: String? = null,
    @Json(name = "id") val id: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbDetailsResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "genres") val genres: List<TmdbGenre>? = null,
    @Json(name = "created_by") val createdBy: List<TmdbCreatedBy>? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "runtime") val runtime: Int? = null,
    @Json(name = "episode_run_time") val episodeRunTime: List<Int>? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "production_companies") val productionCompanies: List<TmdbCompany>? = null,
    @Json(name = "networks") val networks: List<TmdbNetwork>? = null,
    @Json(name = "production_countries") val productionCountries: List<TmdbCountry>? = null,
    @Json(name = "origin_country") val originCountry: List<String>? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "last_air_date") val lastAirDate: String? = null,
    @Json(name = "status") val status: String? = null,
    @Json(name = "belongs_to_collection") val belongsToCollection: TmdbCollectionSummary? = null,
    /** Series: how many seasons it has. */
    @Json(name = "number_of_seasons") val numberOfSeasons: Int? = null,
    /** Series: its seasons (number, name, how many episodes). */
    @Json(name = "seasons") val seasons: List<TmdbSeasonSummary>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbSeasonSummary(
    @Json(name = "season_number") val seasonNumber: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "episode_count") val episodeCount: Int? = null,
    @Json(name = "air_date") val airDate: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCreatedBy(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbGenre(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String
)

@JsonClass(generateAdapter = true)
data class TmdbCompany(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "logo_path") val logoPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbNetwork(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "logo_path") val logoPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCreditsResponse(
    @Json(name = "cast") val cast: List<TmdbCastMember>? = null,
    @Json(name = "crew") val crew: List<TmdbCrewMember>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbAggregateCreditsResponse(
    @Json(name = "cast") val cast: List<TmdbAggregateCastMember>? = null,
    @Json(name = "crew") val crew: List<TmdbAggregateCrewMember>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbAggregateCastMember(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "roles") val roles: List<TmdbAggregateRole>? = null,
    @Json(name = "profile_path") val profilePath: String? = null,
    @Json(name = "total_episode_count") val totalEpisodeCount: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbAggregateRole(
    @Json(name = "character") val character: String? = null,
    @Json(name = "episode_count") val episodeCount: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbAggregateCrewMember(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "jobs") val jobs: List<TmdbAggregateJob>? = null,
    @Json(name = "profile_path") val profilePath: String? = null,
    @Json(name = "department") val department: String? = null,
    @Json(name = "total_episode_count") val totalEpisodeCount: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbAggregateJob(
    @Json(name = "job") val job: String? = null,
    @Json(name = "episode_count") val episodeCount: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCastMember(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "character") val character: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCrewMember(
    @Json(name = "id") val id: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "job") val job: String? = null,
    @Json(name = "department") val department: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null
)

/** A title's names, original language, countries and logos ([TmdbApi.getMovieTitleSpotlight]). */
@JsonClass(generateAdapter = true)
data class TmdbTitleSpotlightResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "origin_country") val originCountry: List<String>? = null,
    @Json(name = "production_countries") val productionCountries: List<TmdbCountry>? = null,
    @Json(name = "images") val images: TmdbImagesResponse? = null
)

@JsonClass(generateAdapter = true)
data class TmdbImagesResponse(
    @Json(name = "logos") val logos: List<TmdbImage>? = null,
    @Json(name = "backdrops") val backdrops: List<TmdbImage>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbMovieReleaseDatesResponse(
    @Json(name = "results") val results: List<TmdbMovieReleaseDateCountry>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbMovieReleaseDateCountry(
    @Json(name = "iso_3166_1") val iso31661: String? = null,
    @Json(name = "release_dates") val releaseDates: List<TmdbMovieReleaseDateItem>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbMovieReleaseDateItem(
    @Json(name = "certification") val certification: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbTvContentRatingsResponse(
    @Json(name = "results") val results: List<TmdbTvContentRatingItem>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbTvContentRatingItem(
    @Json(name = "iso_3166_1") val iso31661: String? = null,
    @Json(name = "rating") val rating: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbImage(
    @Json(name = "file_path") val filePath: String? = null,
    @Json(name = "iso_639_1") val iso6391: String? = null,
    @Json(name = "iso_3166_1") val iso31661: String? = null,
    @Json(name = "width") val width: Int? = null,
    @Json(name = "aspect_ratio") val aspectRatio: Double? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCountry(
    @Json(name = "iso_3166_1") val iso31661: String? = null,
    @Json(name = "name") val name: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbSeasonResponse(
    @Json(name = "season_number") val seasonNumber: Int? = null,
    @Json(name = "episodes") val episodes: List<TmdbEpisode>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbEpisode(
    @Json(name = "episode_number") val episodeNumber: Int? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "still_path") val stillPath: String? = null,
    @Json(name = "air_date") val airDate: String? = null,
    @Json(name = "runtime") val runtime: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbRecommendationsResponse(
    @Json(name = "results") val results: List<TmdbRecommendationResult>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbDiscoverResponse(
    @Json(name = "page") val page: Int? = null,
    @Json(name = "results") val results: List<TmdbDiscoverResult>? = null,
    @Json(name = "total_pages") val totalPages: Int? = null,
    @Json(name = "total_results") val totalResults: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbListDetailsResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "item_count") val itemCount: Int? = null,
    @Json(name = "items") val items: List<TmdbListItem>? = null,
    @Json(name = "page") val page: Int? = null,
    @Json(name = "total_pages") val totalPages: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbListItem(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCompanySearchResponse(
    @Json(name = "page") val page: Int? = null,
    @Json(name = "results") val results: List<TmdbCompanySearchResult>? = null,
    @Json(name = "total_pages") val totalPages: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCompanySearchResult(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "logo_path") val logoPath: String? = null,
    @Json(name = "origin_country") val originCountry: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCollectionSearchResponse(
    @Json(name = "page") val page: Int? = null,
    @Json(name = "results") val results: List<TmdbCollectionSearchResult>? = null,
    @Json(name = "total_pages") val totalPages: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCollectionSearchResult(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "overview") val overview: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbGenresResponse(
    @Json(name = "genres") val genres: List<TmdbGenre>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbKeywordSearchResponse(
    @Json(name = "page") val page: Int? = null,
    @Json(name = "results") val results: List<TmdbKeywordSearchResult>? = null,
    @Json(name = "total_pages") val totalPages: Int? = null
)

@JsonClass(generateAdapter = true)
data class TmdbKeywordSearchResult(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbDiscoverResult(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    @Json(name = "popularity") val popularity: Double? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = null,
    /** "movie", "tv" or "person": only on lists that mix them (trending/all). */
    @Json(name = "media_type") val mediaType: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbRecommendationResult(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "original_language") val originalLanguage: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null
)

// ── Person / Cast Detail DTOs ──

@JsonClass(generateAdapter = true)
data class TmdbPersonResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "biography") val biography: String? = null,
    @Json(name = "birthday") val birthday: String? = null,
    @Json(name = "deathday") val deathday: String? = null,
    @Json(name = "place_of_birth") val placeOfBirth: String? = null,
    @Json(name = "profile_path") val profilePath: String? = null,
    @Json(name = "known_for_department") val knownForDepartment: String? = null,
    @Json(name = "also_known_as") val alsoKnownAs: List<String>? = null,
    @Json(name = "imdb_id") val imdbId: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbPersonCreditsResponse(
    @Json(name = "cast") val cast: List<TmdbPersonCreditCast>? = null,
    @Json(name = "crew") val crew: List<TmdbPersonCreditCrew>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbPersonCreditCast(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "character") val character: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbPersonCreditCrew(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "name") val name: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "original_name") val originalName: String? = null,
    @Json(name = "media_type") val mediaType: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "first_air_date") val firstAirDate: String? = null,
    @Json(name = "job") val job: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCollectionResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "parts") val parts: List<TmdbCollectionPart>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCollectionPart(
    @Json(name = "id") val id: Int,
    @Json(name = "title") val title: String? = null,
    @Json(name = "original_title") val originalTitle: String? = null,
    @Json(name = "overview") val overview: String? = null,
    @Json(name = "release_date") val releaseDate: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null,
    @Json(name = "vote_average") val voteAverage: Double? = null,
    @Json(name = "vote_count") val voteCount: Int? = null,
    @Json(name = "genre_ids") val genreIds: List<Int>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCollectionSummary(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "poster_path") val posterPath: String? = null,
    @Json(name = "backdrop_path") val backdropPath: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbCompanyDetailsResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "headquarters") val headquarters: String? = null,
    @Json(name = "homepage") val homepage: String? = null,
    @Json(name = "logo_path") val logoPath: String? = null,
    @Json(name = "origin_country") val originCountry: String? = null
)

@JsonClass(generateAdapter = true)
data class TmdbNetworkDetailsResponse(
    @Json(name = "id") val id: Int,
    @Json(name = "name") val name: String? = null,
    @Json(name = "headquarters") val headquarters: String? = null,
    @Json(name = "homepage") val homepage: String? = null,
    @Json(name = "logo_path") val logoPath: String? = null,
    @Json(name = "origin_country") val originCountry: String? = null
)

/** RCTV: a title's watch providers, by region code ("US"). */
@JsonClass(generateAdapter = true)
data class TmdbTitleWatchProvidersResponse(
    @Json(name = "results") val results: Map<String, TmdbTitleWatchProviders>? = null
)

/** RCTV: one region's: included in a subscription ([flatrate]), free with ads, free. */
@JsonClass(generateAdapter = true)
data class TmdbTitleWatchProviders(
    @Json(name = "flatrate") val flatrate: List<TmdbWatchProviderEntry>? = null,
    @Json(name = "ads") val ads: List<TmdbWatchProviderEntry>? = null,
    @Json(name = "free") val free: List<TmdbWatchProviderEntry>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbWatchProviderListResponse(
    @Json(name = "results") val results: List<TmdbWatchProviderEntry>? = null
)

@JsonClass(generateAdapter = true)
data class TmdbWatchProviderEntry(
    @Json(name = "provider_id") val providerId: Int,
    @Json(name = "provider_name") val providerName: String? = null,
    @Json(name = "logo_path") val logoPath: String? = null,
    @Json(name = "display_priority") val displayPriority: Int? = null
)
