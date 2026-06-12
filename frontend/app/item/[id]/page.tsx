"use client"

/**
 * Item Detail Page — /app/item/[id]/page.tsx
 *
 * This page shows full details for a single menu item.
 * It's reached by clicking any item card in the menu browser.
 *
 * Three things happen here:
 * 1. Load the item from the menu (we fetch by ID from the menu endpoint)
 * 2. Let the user rate or favorite the item (fires events to the backend)
 * 3. Show "People Also Ate" — similar items powered by co-occurrence
 */

import { use, useState, useEffect } from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { ChevronLeft, Heart, Star, Users } from "lucide-react"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { getSimilar, postEvent, getFavorites, getRatings, SCHOOL_ID_TO_DB_ID, type MenuItem } from "@/lib/api"
import { getUserId } from "@/lib/auth"
import { cn } from "@/lib/utils"

// ── Types ──────────────────────────────────────────────────────────────────

/**
 * NutritionRow — one row in the nutrition facts panel.
 * label: the nutrient name shown to the user
 * value: the numeric amount (may be null if API didn't return it)
 * unit: g, mg, kcal etc.
 * highlight: makes the row bold (used for calories)
 */
interface NutritionRow {
  label: string
  value: number | null | undefined
  unit: string
  highlight?: boolean
}

// ── Page component ─────────────────────────────────────────────────────────

export default function ItemDetailPage({
  params,
}: {
  params: Promise<{ id: string }>
}) {
  /**
   * Next.js 15 changed params to be a Promise.
   * use() unwraps it synchronously inside a Client Component.
   */
  const { id } = use(params)
  const searchParams = useSearchParams()
  const router = useRouter()

  /**
   * We pass the hallId and item data as search params from the menu page
   * so we don't need an extra API call just to show the item.
   * Format: /item/160?hallId=44401&name=Grilled+Chicken&calories=320...
   */
  const hallId = Number(searchParams.get("hallId") ?? 44401)
  const dbHallId = SCHOOL_ID_TO_DB_ID[hallId] ?? 1
  const itemId = Number(id)

  // ── State ──────────────────────────────────────────────────────────────

  /**
   * We reconstruct the item from search params passed by the menu page.
   * This avoids needing a separate "get item by ID" endpoint.
   */
  const [item] = useState<MenuItem>(() => ({
    id: itemId,
    name: searchParams.get("name") ?? "Menu Item",
    station: searchParams.get("station") ?? undefined,
    calories: searchParams.get("calories") ? Number(searchParams.get("calories")) : undefined,
    g_protein: searchParams.get("protein") ? Number(searchParams.get("protein")) : undefined,
    g_carbs: searchParams.get("carbs") ? Number(searchParams.get("carbs")) : undefined,
    g_fat: searchParams.get("fat") ? Number(searchParams.get("fat")) : undefined,
    g_fiber: searchParams.get("fiber") ? Number(searchParams.get("fiber")) : undefined,
    mg_sodium: searchParams.get("sodium") ? Number(searchParams.get("sodium")) : undefined,
    is_vegan: searchParams.get("vegan") === "true",
    is_meatless: searchParams.get("meatless") === "true",
    is_halal: searchParams.get("halal") === "true",
    is_gluten_friendly: searchParams.get("gluten") === "true",
    contains_milk: searchParams.get("milk") === "true",
    contains_eggs: searchParams.get("eggs") === "true",
    contains_wheat: searchParams.get("wheat") === "true",
    contains_peanuts: searchParams.get("peanuts") === "true",
    contains_tree_nuts: searchParams.get("treenuts") === "true",
    contains_soy: searchParams.get("soy") === "true",
    contains_fish: searchParams.get("fish") === "true",
    contains_shellfish: searchParams.get("shellfish") === "true",
    contains_sesame: searchParams.get("sesame") === "true",
    serving_size_amount: searchParams.get("servingAmount") ?? undefined,
    serving_size_unit: searchParams.get("servingUnit") ?? undefined,
  }))

  // User's current rating (0 = not rated yet, 1-5 = star rating)
  const [rating, setRating] = useState(0)

  // Which star the user is hovering over (for hover preview)
  const [hoverRating, setHoverRating] = useState(0)

  // Whether this item is favorited
  const [favorited, setFavorited] = useState(false)

  // "People Also Ate" items from the co-occurrence endpoint
  const [similar, setSimilar] = useState<MenuItem[]>([])

  // ── Effects ────────────────────────────────────────────────────────────

  /**
   * On page load:
   * 1. Fire a "view" event so the backend knows the user saw this item
   * 2. Load similar items from the co-occurrence sorted set
   */
  useEffect(() => {
    postEvent(getUserId(), itemId, dbHallId, "view")

    getSimilar(itemId, hallId, 6)
      .then(setSimilar)
      .catch(() => setSimilar([]))

    // Hydrate favorite and rating state from the server
    getFavorites()
      .then((ids) => setFavorited(ids.includes(itemId)))
      .catch(() => {})

    getRatings()
      .then((map) => { if (map[itemId] != null) setRating(map[itemId]) })
      .catch(() => {})
  }, [itemId, hallId])

  // ── Handlers ───────────────────────────────────────────────────────────

  /**
   * When the user clicks a star:
   * - Update local state to show the filled stars
   * - Fire a "rate" event to the backend with the numeric rating
   * - The backend writes this to Postgres AND pushes to Redis Stream
   * - TrendingConsumer picks it up and adds score = rating * 0.6 * decay
   */
  const handleRate = async (stars: number) => {
    setRating(stars)
    await postEvent(getUserId(), itemId, dbHallId, "rate", stars)
  }

  /**
   * When the user taps the heart button:
   * - Toggle favorite state locally (optimistic update)
   * - Fire "favorite" or "click" event to the backend
   * - "favorite" has base_weight=5, the strongest signal in the system
   */
  const handleFavorite = async () => {
    const next = !favorited
    setFavorited(next)
    await postEvent(getUserId(), itemId, dbHallId, next ? "favorite" : "unfavorite")
  }

  // ── Nutrition rows ─────────────────────────────────────────────────────

  /**
   * Build the nutrition facts table rows.
   * We skip any row where the value is null/undefined — no point showing
   * "Protein: —" if the API didn't return protein data.
   */
  const nutritionRows: NutritionRow[] = [
    { label: "Calories", value: item.calories, unit: "kcal", highlight: true },
    { label: "Protein", value: item.g_protein, unit: "g" },
    { label: "Total Carbs", value: item.g_carbs, unit: "g" },
    { label: "Dietary Fiber", value: item.g_fiber, unit: "g" },
    { label: "Total Fat", value: item.g_fat, unit: "g" },
    { label: "Sodium", value: item.mg_sodium, unit: "mg" },
  ].filter((row) => row.value != null)

  // ── Allergens ──────────────────────────────────────────────────────────

  /**
   * Build a list of allergen warnings to display.
   * These come from behavior=1 icons in the Nutrislice API response,
   * stored as boolean columns in our menu_items table.
   */
  const allergens = [
    { label: "Milk", present: item.contains_milk },
    { label: "Eggs", present: item.contains_eggs },
    { label: "Wheat", present: item.contains_wheat },
    { label: "Peanuts", present: item.contains_peanuts },
    { label: "Tree Nuts", present: item.contains_tree_nuts },
    { label: "Soy", present: item.contains_soy },
    { label: "Fish", present: item.contains_fish },
    { label: "Shellfish", present: item.contains_shellfish },
    { label: "Sesame", present: item.contains_sesame },
  ].filter((a) => a.present)

  // ── Render ─────────────────────────────────────────────────────────────

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-2xl mx-auto p-6">

        {/* Back button — goes back to previous page */}
        <button
          onClick={() => router.back()}
          className="flex items-center gap-1 text-gray-500 hover:text-gray-900 text-sm mb-6"
        >
          <ChevronLeft className="w-4 h-4" />
          Back to Menu
        </button>

        {/* ── Item header ─────────────────────────────────────────────── */}
        <Card className="p-6 mb-4 bg-white">
          <div className="flex items-start justify-between gap-4">
            <div className="flex-1">
              <h1 className="text-2xl font-bold text-gray-900 mb-1">{item.name}</h1>
              {item.station && (
                <p className="text-sm text-gray-500 mb-3">{item.station}</p>
              )}

              {/* Dietary badges — behavior=2 icons from Nutrislice */}
              <div className="flex flex-wrap gap-2">
                {item.is_vegan && (
                  <Badge className="bg-green-100 text-green-700 border-0">🌱 Vegan</Badge>
                )}
                {item.is_meatless && !item.is_vegan && (
                  <Badge className="bg-green-100 text-green-700 border-0">🥦 Meatless</Badge>
                )}
                {item.is_halal && (
                  <Badge className="bg-blue-100 text-blue-700 border-0">☪️ Halal</Badge>
                )}
                {item.is_gluten_friendly && (
                  <Badge className="bg-yellow-100 text-yellow-700 border-0">🌾 Gluten Friendly</Badge>
                )}
              </div>
            </div>

            {/* Favorite button */}
            <button
              onClick={handleFavorite}
              className="p-3 rounded-full hover:bg-gray-100 transition-colors flex-shrink-0"
            >
              <Heart
                className={cn(
                  "w-6 h-6 transition-colors",
                  favorited ? "fill-red-500 text-red-500" : "text-gray-400"
                )}
              />
            </button>
          </div>
        </Card>

        {/* ── Nutrition facts ──────────────────────────────────────────── */}
        {nutritionRows.length > 0 && (
          <Card className="p-6 mb-4 bg-white">
            <h2 className="text-lg font-bold text-gray-900 mb-4 border-b border-gray-200 pb-2">
              Nutrition Facts
            </h2>

            {/* Serving size if available */}
            {item.serving_size_amount && (
              <p className="text-sm text-gray-500 mb-3">
                Serving size: {item.serving_size_amount} {item.serving_size_unit}
              </p>
            )}

            {/* Nutrition rows */}
            <div className="space-y-2">
              {nutritionRows.map((row) => (
                <div
                  key={row.label}
                  className={cn(
                    "flex justify-between items-center py-1.5",
                    row.highlight
                      ? "border-b-4 border-gray-900 pb-2 mb-2"
                      : "border-b border-gray-100"
                  )}
                >
                  <span
                    className={cn(
                      "text-gray-700",
                      row.highlight ? "text-lg font-bold" : "text-sm"
                    )}
                  >
                    {row.label}
                  </span>
                  <span
                    className={cn(
                      "text-gray-900",
                      row.highlight ? "text-lg font-bold" : "text-sm font-medium"
                    )}
                  >
                    {row.value}
                    <span className="text-gray-500 font-normal ml-0.5 text-xs">
                      {row.unit}
                    </span>
                  </span>
                </div>
              ))}
            </div>

            {/* Allergen warnings */}
            {allergens.length > 0 && (
              <div className="mt-4 pt-4 border-t border-gray-200">
                <p className="text-xs font-semibold text-gray-700 mb-2">CONTAINS:</p>
                <div className="flex flex-wrap gap-1.5">
                  {allergens.map((a) => (
                    <Badge
                      key={a.label}
                      variant="outline"
                      className="text-red-600 border-red-200 bg-red-50 text-xs"
                    >
                      ⚠️ {a.label}
                    </Badge>
                  ))}
                </div>
              </div>
            )}
          </Card>
        )}

        {/* ── Rating widget ────────────────────────────────────────────── */}
        {/**
         * Star rating 1-5.
         * onMouseEnter/Leave drives the hover preview so users can see
         * what score they're about to give before clicking.
         * onClick commits the rating and fires the event to the backend.
         */}
        <Card className="p-6 mb-4 bg-white">
          <h2 className="text-lg font-bold text-gray-900 mb-1">Rate this item</h2>
          <p className="text-sm text-gray-500 mb-4">
            Your rating helps personalize recommendations for everyone
          </p>
          <div className="flex items-center gap-2">
            {[1, 2, 3, 4, 5].map((star) => (
              <button
                key={star}
                onMouseEnter={() => setHoverRating(star)}
                onMouseLeave={() => setHoverRating(0)}
                onClick={() => handleRate(star)}
                className="transition-transform hover:scale-110"
              >
                <Star
                  className={cn(
                    "w-8 h-8 transition-colors",
                    star <= (hoverRating || rating)
                      ? "fill-yellow-400 text-yellow-400"
                      : "text-gray-300"
                  )}
                />
              </button>
            ))}
            {rating > 0 && (
              <span className="text-sm text-gray-500 ml-2">
                You rated this {rating}/5
              </span>
            )}
          </div>
        </Card>

        {/* ── People Also Ate carousel ─────────────────────────────────── */}
        {/**
         * Similar items from GET /api/v1/recommendations/similar
         * These come from the cooccur:{itemId} sorted set in Redis —
         * items that other users interacted with in the same session.
         * Only shows if the co-occurrence data exists (needs real interactions).
         */}
        {similar.length > 0 && (
          <Card className="p-6 bg-white">
            <div className="flex items-center gap-2 mb-4">
              <Users className="w-5 h-5 text-blue-600" />
              <h2 className="text-lg font-bold text-gray-900">People Also Ate</h2>
            </div>
            <div className="space-y-3">
              {similar.map((s) => (
                <button
                  key={s.id}
                  onClick={() => {
                    // Fire click event for the similar item
                    postEvent(getUserId(), s.id, dbHallId, "click")
                    // Navigate to that item's detail page
                    const params = new URLSearchParams({
                      hallId: String(hallId),
                      name: s.name,
                      ...(s.station && { station: s.station }),
                      ...(s.calories != null && { calories: String(s.calories) }),
                      ...(s.g_protein != null && { protein: String(s.g_protein) }),
                      ...(s.g_carbs != null && { carbs: String(s.g_carbs) }),
                      ...(s.g_fat != null && { fat: String(s.g_fat) }),
                      ...(s.g_fiber != null && { fiber: String(s.g_fiber) }),
                      vegan: String(s.is_vegan),
                      meatless: String(s.is_meatless),
                      halal: String(s.is_halal),
                      gluten: String(s.is_gluten_friendly),
                    })
                    router.push(`/item/${s.id}?${params.toString()}`)
                  }}
                  className="w-full flex items-center justify-between p-3 rounded-lg hover:bg-gray-50 transition-colors text-left border border-gray-100"
                >
                  <div>
                    <p className="font-medium text-gray-900 text-sm">{s.name}</p>
                    <div className="flex gap-2 text-xs text-gray-500 mt-0.5">
                      {s.calories != null && <span>{s.calories} cal</span>}
                      {s.station && <span>· {s.station}</span>}
                    </div>
                  </div>
                  <div className="flex gap-1">
                    {s.is_vegan && <span className="text-xs">🌱</span>}
                    {s.is_halal && <span className="text-xs">☪️</span>}
                  </div>
                </button>
              ))}
            </div>
          </Card>
        )}

        {/* Empty state for People Also Ate — shown until co-occurrence data builds up */}
        {similar.length === 0 && (
          <Card className="p-6 bg-white text-center text-gray-400">
            <Users className="w-6 h-6 mx-auto mb-2 opacity-40" />
            <p className="text-sm">
              People Also Ate will appear here as more students interact with the menu
            </p>
          </Card>
        )}

      </div>
    </div>
  )
}
