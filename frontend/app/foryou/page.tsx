"use client"

import { useState, useEffect, useCallback } from "react"
import { Sparkles, RefreshCw, Heart } from "lucide-react"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { getForYou, postEvent, currentDaypart, daypartLabel, type MenuItem } from "@/lib/api"
import { getUserId } from "@/lib/auth"
import { cn } from "@/lib/utils"

const HALLS = [
  { schoolId: 44401, name: "Bolton" },
  { schoolId: 44402, name: "O-House" },
  { schoolId: 44403, name: "Snelling" },
  { schoolId: 44404, name: "Niche" },
  { schoolId: 44405, name: "Village Summit" },
]

export default function ForYouPage() {
  const [selectedHall, setSelectedHall] = useState(HALLS[0])
  const [daypart] = useState(currentDaypart())
  const [items, setItems] = useState<MenuItem[]>([])
  const [loading, setLoading] = useState(true)
  const [favorites, setFavorites] = useState<Set<number>>(new Set())

  const fetchForYou = useCallback(async () => {
    setLoading(true)
    try {
      const data = await getForYou(getUserId(), selectedHall.schoolId, daypart, 10)
      setItems(data)
      data.forEach((item) => postEvent(getUserId(), item.id, selectedHall.schoolId, "view"))
    } catch {
      setItems([])
    } finally {
      setLoading(false)
    }
  }, [selectedHall, daypart])

  useEffect(() => {
    fetchForYou()
    const interval = setInterval(fetchForYou, 120_000) // refresh every 2 min
    return () => clearInterval(interval)
  }, [fetchForYou])

  const handleFavorite = async (item: MenuItem) => {
    const next = new Set(favorites)
    if (next.has(item.id)) {
      next.delete(item.id)
      await postEvent(getUserId(), item.id, selectedHall.schoolId, "click")
    } else {
      next.add(item.id)
      await postEvent(getUserId(), item.id, selectedHall.schoolId, "favorite")
    }
    setFavorites(next)
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-4xl mx-auto p-6">
        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
              <Sparkles className="w-6 h-6 text-blue-600" />
              For You
            </h1>
            <p className="text-sm text-gray-500 mt-0.5">
              Personalized for {daypartLabel(daypart)} · refreshes every 2 min
            </p>
          </div>
          <button
            onClick={fetchForYou}
            className="p-2 rounded-full hover:bg-gray-100 transition-colors"
          >
            <RefreshCw className={cn("w-5 h-5 text-gray-500", loading && "animate-spin")} />
          </button>
        </div>

        {/* Hall selector */}
        <div className="flex flex-wrap gap-2 mb-6">
          {HALLS.map((hall) => (
            <button
              key={hall.schoolId}
              onClick={() => setSelectedHall(hall)}
              className={cn(
                "px-4 py-1.5 rounded-full text-sm font-medium border transition-colors",
                selectedHall.schoolId === hall.schoolId
                  ? "bg-blue-600 text-white border-blue-600"
                  : "bg-white text-gray-700 border-gray-300 hover:bg-gray-50"
              )}
            >
              {hall.name}
            </button>
          ))}
        </div>

        {/* Items */}
        {loading ? (
          <div className="space-y-3">
            {[1, 2, 3, 4, 5].map((i) => (
              <div key={i} className="h-24 bg-gray-200 rounded-xl animate-pulse" />
            ))}
          </div>
        ) : items.length === 0 ? (
          <Card className="p-10 text-center">
            <Sparkles className="w-8 h-8 text-gray-300 mx-auto mb-3" />
            <p className="text-gray-500 font-medium">Not enough data yet</p>
            <p className="text-gray-400 text-sm mt-1">
              Favorite some items in the menu and come back!
            </p>
          </Card>
        ) : (
          <div className="space-y-3">
            {items.map((item, idx) => (
              <Card key={item.id} className="flex items-center gap-4 p-4 bg-white hover:shadow-sm transition-shadow">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1 flex-wrap">
                    <span className="font-medium text-gray-900">{item.name}</span>
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
                    {item.station && <span className="text-gray-400">· {item.station}</span>}
                  </div>
                  <p className="text-xs text-blue-500 mt-1">
                    {idx < 3 ? "✨ Top pick for you" : "Based on your preferences"}
                  </p>
                </div>
                <button
                  onClick={() => handleFavorite(item)}
                  className="p-2 rounded-full hover:bg-gray-100 transition-colors flex-shrink-0"
                >
                  <Heart
                    className={cn(
                      "w-5 h-5 transition-colors",
                      favorites.has(item.id) ? "fill-red-500 text-red-500" : "text-gray-400"
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
