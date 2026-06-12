"use client"

import { useState, useEffect } from "react"
import { useRouter } from "next/navigation"
import { Heart } from "lucide-react"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { getFavoriteItems, postEvent, SCHOOL_ID_TO_DB_ID, type MenuItem } from "@/lib/api"
import { getUserId, isLoggedIn } from "@/lib/auth"
import { cn } from "@/lib/utils"

export default function FavoritesPage() {
  const router = useRouter()
  const [items, setItems] = useState<MenuItem[]>([])
  const [loading, setLoading] = useState(true)
  const [unfavoriting, setUnfavoriting] = useState<Set<number>>(new Set())

  useEffect(() => {
    if (!isLoggedIn()) {
      router.push("/login")
      return
    }
    getFavoriteItems()
      .then(setItems)
      .catch(() => setItems([]))
      .finally(() => setLoading(false))
  }, [router])

  const handleUnfavorite = async (item: MenuItem) => {
    setUnfavoriting((prev) => new Set(prev).add(item.id))
    // Use hall 1 as default since we don't store which hall it came from
    await postEvent(getUserId(), item.id, 1, "unfavorite").catch(() => {})
    setItems((prev) => prev.filter((i) => i.id !== item.id))
    setUnfavoriting((prev) => { const s = new Set(prev); s.delete(item.id); return s })
  }

  const handleItemClick = (item: MenuItem) => {
    const params = new URLSearchParams({
      hallId: String(44401),
      name: item.name,
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
    })
    router.push(`/item/${item.id}?${params.toString()}`)
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-2xl mx-auto p-6">
        <div className="flex items-center gap-3 mb-6">
          <Heart className="w-6 h-6 fill-red-500 text-red-500" />
          <h1 className="text-2xl font-bold text-gray-900">My Favorites</h1>
        </div>

        {loading ? (
          <div className="space-y-3">
            {[1, 2, 3, 4].map((i) => (
              <div key={i} className="h-20 bg-gray-200 rounded-xl animate-pulse" />
            ))}
          </div>
        ) : items.length === 0 ? (
          <Card className="p-12 text-center">
            <Heart className="w-10 h-10 text-gray-200 mx-auto mb-4" />
            <p className="text-gray-500 font-medium">No favorites yet</p>
            <p className="text-gray-400 text-sm mt-1">
              Tap the heart on any menu item to save it here
            </p>
          </Card>
        ) : (
          <div className="space-y-2">
            {items.map((item) => (
              <Card
                key={item.id}
                className="flex items-center justify-between p-4 bg-white hover:shadow-sm transition-shadow cursor-pointer"
                onClick={() => handleItemClick(item)}
              >
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1 flex-wrap">
                    <span className="font-medium text-gray-900 truncate">{item.name}</span>
                    {item.is_vegan && (
                      <Badge variant="outline" className="text-green-700 border-green-200 bg-green-50 text-xs">
                        🌱 Vegan
                      </Badge>
                    )}
                    {item.is_halal && (
                      <Badge variant="outline" className="text-blue-700 border-blue-200 bg-blue-50 text-xs">
                        ☪️ Halal
                      </Badge>
                    )}
                  </div>
                  <div className="flex gap-3 text-sm text-gray-500">
                    {item.calories != null && <span>{item.calories} cal</span>}
                    {item.g_protein != null && <span>{item.g_protein}g protein</span>}
                    {item.g_carbs != null && <span>{item.g_carbs}g carbs</span>}
                  </div>
                </div>
                <button
                  onClick={(e) => { e.stopPropagation(); handleUnfavorite(item) }}
                  disabled={unfavoriting.has(item.id)}
                  className="ml-3 p-2 rounded-full hover:bg-gray-100 transition-colors flex-shrink-0"
                >
                  <Heart
                    className={cn(
                      "w-5 h-5 transition-colors",
                      unfavoriting.has(item.id)
                        ? "text-gray-300"
                        : "fill-red-500 text-red-500"
                    )}
                  />
                </button>
              </Card>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
