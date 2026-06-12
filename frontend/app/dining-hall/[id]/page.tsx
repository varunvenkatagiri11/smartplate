"use client"

import { use, useState, useEffect, useCallback } from "react"
import { useRouter } from "next/navigation"
import { ChevronLeft, Search, Filter, Heart } from "lucide-react"
import { Card } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { getMenu, postEvent, getFavorites, HALL_SLUG_TO_ID, SCHOOL_ID_TO_DB_ID, type MenuItem } from "@/lib/api"
import { getUserId } from "@/lib/auth"
import { cn } from "@/lib/utils"

const HALL_NAMES: Record<string, string> = {
  bolton: "Bolton Dining Commons",
  oglethorpe: "O-House Dining",
  snelling: "Snelling Dining Commons",
  niche: "Niche",
  "village-summit": "Village Summit",
}

const HALL_IMAGES: Record<string, string> = {
  bolton: "/images/bolton-dining.jpg",
  oglethorpe: "/images/oglethorpe-dining.jpg",
  snelling: "/images/snelling-dining.webp",
  niche: "/images/niche-dining.jpg",
  "village-summit": "/images/village-summit.jpg",
}

type MealTab = "All" | "Breakfast" | "Lunch / Dinner"

export default function DiningHallPage({
  params,
}: {
  params: Promise<{ id: string }>
}) {
  const { id: hallSlug } = use(params)
  const router = useRouter()
  const schoolId = HALL_SLUG_TO_ID[hallSlug]

  const [activeMeal, setActiveMeal] = useState<MealTab>("All")
  const [search, setSearch] = useState("")
  const [items, setItems] = useState<MenuItem[]>([])
  const [loading, setLoading] = useState(true)
  const [showFilters, setShowFilters] = useState(false)
  const [filters, setFilters] = useState({
    vegan: false,
    meatless: false,
    halal: false,
    glutenFriendly: false,
  })

  /**
   * favorites is a Set of item IDs the user has hearted.
   * Stored at PAGE level so it survives MenuItemCard re-renders.
   * When the parent re-renders (e.g. search changes), MenuItemCard
   * gets the correct favorited prop instead of resetting to false.
   */
  const [favorites, setFavorites] = useState<Set<number>>(new Set())

  // Hydrate favorites from the server once on mount
  useEffect(() => {
    getFavorites()
      .then((ids) => setFavorites(new Set(ids)))
      .catch(() => {})
  }, [])

  const fetchItems = useCallback(async () => {
    if (!schoolId) return
    setLoading(true)
    try {
      const options = {
        isVegan: filters.vegan || undefined,
        isMeatless: filters.meatless || undefined,
        isHalal: filters.halal || undefined,
        isGlutenFriendly: filters.glutenFriendly || undefined,
      }
      if (activeMeal === "All") {
        const [breakfast, lunch] = await Promise.all([
          getMenu(schoolId, "breakfast", undefined, options),
          getMenu(schoolId, "lunch_dinner", undefined, options),
        ])
        setItems([...breakfast, ...lunch])
      } else if (activeMeal === "Breakfast") {
        setItems(await getMenu(schoolId, "breakfast", undefined, options))
      } else {
        setItems(await getMenu(schoolId, "lunch_dinner", undefined, options))
      }
    } catch {
      setItems([])
    } finally {
      setLoading(false)
    }
  }, [schoolId, activeMeal, filters])

  useEffect(() => { fetchItems() }, [fetchItems])

  /**
   * Toggle favorite for an item.
   * Updates the Set at page level and fires event to backend.
   * Uses SCHOOL_ID_TO_DB_ID to convert schoolId → DB id for the event.
   */
  const handleFavorite = (item: MenuItem) => {
    const dbHallId = SCHOOL_ID_TO_DB_ID[schoolId] ?? 1
    const userId = getUserId()

    setFavorites(prev => {
      const next = new Set(prev)
      if (next.has(item.id)) {
        next.delete(item.id)
        postEvent(userId, item.id, dbHallId, "unfavorite").catch(() => {})
      } else {
        next.add(item.id)
        postEvent(userId, item.id, dbHallId, "favorite").catch(() => {})
      }
      return next
    })
  }

  const filtered = items.filter((item) => {
    if (search && !item.name.toLowerCase().includes(search.toLowerCase())) return false
    return true
  })

  const grouped = filtered.reduce<Record<string, MenuItem[]>>((acc, item) => {
    const key = item.station ?? "Other"
    if (!acc[key]) acc[key] = []
    acc[key].push(item)
    return acc
  }, {})

  if (!schoolId) return <div className="p-8 text-center">Dining hall not found</div>

  return (
    <div className="min-h-screen bg-gray-50">
      {/* Hero */}
      <div className="relative h-56 w-full">
        <img
          src={HALL_IMAGES[hallSlug] || "/placeholder.svg"}
          alt={HALL_NAMES[hallSlug]}
          className="w-full h-full object-cover"
        />
        <div className="absolute inset-0 bg-black/50" />
        <div className="absolute bottom-5 left-5 right-5">
          <button
            onClick={() => router.push("/")}
            className="flex items-center gap-1 text-white/80 hover:text-white text-sm mb-2"
          >
            <ChevronLeft className="w-4 h-4" />
            Back to Dining Halls
          </button>
          <h1 className="text-white text-2xl font-bold">{HALL_NAMES[hallSlug]}</h1>
        </div>
      </div>

      <div className="max-w-4xl mx-auto p-6">
        {/* Meal tabs */}
        <div className="flex gap-2 mb-5">
          {(["All", "Breakfast", "Lunch / Dinner"] as MealTab[]).map((tab) => (
            <button
              key={tab}
              onClick={() => setActiveMeal(tab)}
              className={cn(
                "px-5 py-2 rounded-full text-sm font-medium transition-colors",
                activeMeal === tab
                  ? "bg-blue-600 text-white"
                  : "bg-white text-gray-700 border border-gray-300 hover:bg-gray-50"
              )}
            >
              {tab}
            </button>
          ))}
        </div>

        {/* Search + filter bar */}
        <div className="flex gap-3 mb-4">
          <div className="relative flex-1">
            <Search className="absolute left-3 top-1/2 -translate-y-1/2 w-4 h-4 text-gray-400" />
            <Input
              placeholder="Search items..."
              value={search}
              onChange={(e) => setSearch(e.target.value)}
              className="pl-9"
            />
          </div>
          <Button
            variant="outline"
            onClick={() => setShowFilters(!showFilters)}
            className={cn(showFilters && "bg-blue-50 border-blue-300")}
          >
            <Filter className="w-4 h-4 mr-1.5" />
            Filters
          </Button>
        </div>

        {/* Filter chips */}
        {showFilters && (
          <div className="flex flex-wrap gap-2 mb-5 p-4 bg-white rounded-xl border border-gray-200">
            {[
              { key: "vegan", label: "Vegan", icon: "🌱" },
              { key: "meatless", label: "Meatless", icon: "🥦" },
              { key: "halal", label: "Halal", icon: "☪️" },
              { key: "glutenFriendly", label: "Gluten Friendly", icon: "🌾" },
            ].map(({ key, label, icon }) => (
              <button
                key={key}
                onClick={() => setFilters((f) => ({ ...f, [key]: !f[key as keyof typeof f] }))}
                className={cn(
                  "flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm font-medium border transition-colors",
                  filters[key as keyof typeof filters]
                    ? "bg-green-600 text-white border-green-600"
                    : "bg-white text-gray-700 border-gray-300 hover:bg-gray-50"
                )}
              >
                <span>{icon}</span>
                {label}
              </button>
            ))}
          </div>
        )}

        {/* Content */}
        {loading ? (
          <div className="space-y-3">
            {[1, 2, 3].map((i) => (
              <div key={i} className="h-24 bg-gray-200 rounded-xl animate-pulse" />
            ))}
          </div>
        ) : Object.keys(grouped).length === 0 ? (
          <Card className="p-10 text-center text-gray-500">
            No items for this hall and meal period today.
          </Card>
        ) : (
          <div className="space-y-6">
            {Object.entries(grouped).map(([station, stationItems]) => (
              <div key={station}>
                <h2 className="text-sm font-semibold text-gray-500 uppercase tracking-wide mb-3">
                  {station}
                </h2>
                <div className="space-y-2">
                  {stationItems.map((item) => (
                    <MenuItemCard
                      key={item.id}
                      item={item}
                      hallDbId={schoolId}
                      favorited={favorites.has(item.id)}
                      onFavorite={() => handleFavorite(item)}
                    />
                  ))}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}

function MenuItemCard({
  item,
  hallDbId,
  favorited,
  onFavorite,
}: {
  item: MenuItem
  hallDbId: number
  favorited: boolean
  onFavorite: () => void
}) {
  const router = useRouter()

  const buildItemUrl = () => {
    const params = new URLSearchParams({
      hallId: String(hallDbId),
      name: item.name,
      ...(item.station && { station: item.station }),
      ...(item.calories != null && { calories: String(item.calories) }),
      ...(item.g_protein != null && { protein: String(item.g_protein) }),
      ...(item.g_carbs != null && { carbs: String(item.g_carbs) }),
      ...(item.g_fat != null && { fat: String(item.g_fat) }),
      ...(item.g_fiber != null && { fiber: String(item.g_fiber) }),
      ...(item.mg_sodium != null && { sodium: String(item.mg_sodium) }),
      vegan: String(item.is_vegan),
      meatless: String(item.is_meatless),
      halal: String(item.is_halal),
      gluten: String(item.is_gluten_friendly),
      milk: String(item.contains_milk),
      eggs: String(item.contains_eggs),
      wheat: String(item.contains_wheat),
      peanuts: String(item.contains_peanuts),
      treenuts: String(item.contains_tree_nuts),
      soy: String(item.contains_soy),
      fish: String(item.contains_fish),
      shellfish: String(item.contains_shellfish),
      sesame: String(item.contains_sesame),
      ...(item.serving_size_amount && { servingAmount: item.serving_size_amount }),
      ...(item.serving_size_unit && { servingUnit: item.serving_size_unit }),
    })
    return `/item/${item.id}?${params.toString()}`
  }

  const handleCardClick = () => {
    postEvent(
      getUserId(),
      item.id,
      SCHOOL_ID_TO_DB_ID[hallDbId] ?? 1,
      "click"
    ).catch(() => {})
    router.push(buildItemUrl())
  }

  const handleFavoriteClick = (e: React.MouseEvent) => {
    e.stopPropagation()
    onFavorite()
  }

  return (
    <Card
      className="flex items-center justify-between p-4 hover:shadow-sm transition-shadow bg-white cursor-pointer"
      onClick={handleCardClick}
    >
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-2 mb-1">
          <span className="font-medium text-gray-900 truncate">{item.name}</span>
          <div className="flex gap-1 flex-shrink-0">
            {item.is_vegan && <span title="Vegan" className="text-xs">🌱</span>}
            {item.is_meatless && !item.is_vegan && <span title="Meatless" className="text-xs">🥦</span>}
            {item.is_halal && <span title="Halal" className="text-xs">☪️</span>}
            {item.is_gluten_friendly && <span title="Gluten Friendly" className="text-xs">🌾</span>}
          </div>
        </div>
        <div className="flex items-center gap-3 text-sm text-gray-500">
          {item.calories != null && <span>{item.calories} cal</span>}
          {item.g_protein != null && <span>{item.g_protein}g protein</span>}
          {item.g_carbs != null && <span>{item.g_carbs}g carbs</span>}
          {item.g_fat != null && <span>{item.g_fat}g fat</span>}
        </div>
      </div>
      <button
        onClick={handleFavoriteClick}
        className="ml-3 p-2 rounded-full hover:bg-gray-100 transition-colors flex-shrink-0"
      >
        <Heart
          className={cn(
            "w-5 h-5 transition-colors",
            favorited ? "fill-red-500 text-red-500" : "text-gray-400"
          )}
        />
      </button>
    </Card>
  )
}
