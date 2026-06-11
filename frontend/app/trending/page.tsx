"use client"

import { useState, useEffect, useCallback } from "react"
import { TrendingUp, RefreshCw } from "lucide-react"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"
import { getTrending, postEvent, currentDaypart, daypartLabel, type TrendingItem } from "@/lib/api"
import { getUserId } from "@/lib/auth"
import { cn } from "@/lib/utils"

const HALLS = [
  { schoolId: 44401, name: "Bolton", slug: "bolton" },
  { schoolId: 44402, name: "O-House", slug: "oglethorpe" },
  { schoolId: 44403, name: "Snelling", slug: "snelling" },
  { schoolId: 44404, name: "Niche", slug: "niche" },
  { schoolId: 44405, name: "Village Summit", slug: "village-summit" },
]

export default function TrendingPage() {
  const [selectedHall, setSelectedHall] = useState(HALLS[0])
  const [daypart, setDaypart] = useState(currentDaypart())
  const [items, setItems] = useState<TrendingItem[]>([])
  const [loading, setLoading] = useState(true)
  const [lastUpdated, setLastUpdated] = useState<Date>(new Date())

  const fetchTrending = useCallback(async () => {
    setLoading(true)
    try {
      const data = await getTrending(selectedHall.schoolId, daypart, 10)
      setItems(data)
      setLastUpdated(new Date())
      // Fire view events
      data.forEach((item) => postEvent(getUserId(), item.id, selectedHall.schoolId, "view"))
    } catch {
      setItems([])
    } finally {
      setLoading(false)
    }
  }, [selectedHall, daypart])

  useEffect(() => {
    fetchTrending()
    const interval = setInterval(fetchTrending, 30_000)
    return () => clearInterval(interval)
  }, [fetchTrending])

  return (
    <div className="min-h-screen bg-gray-50">
      <div className="max-w-4xl mx-auto p-6">
        {/* Header */}
        <div className="flex items-center justify-between mb-6">
          <div>
            <h1 className="text-2xl font-bold text-gray-900 flex items-center gap-2">
              <TrendingUp className="w-6 h-6 text-blue-600" />
              Trending Now
            </h1>
            <p className="text-sm text-gray-500 mt-0.5">
              Updated {lastUpdated.toLocaleTimeString()} · refreshes every 30s
            </p>
          </div>
          <button
            onClick={fetchTrending}
            className="p-2 rounded-full hover:bg-gray-100 transition-colors"
          >
            <RefreshCw className={cn("w-5 h-5 text-gray-500", loading && "animate-spin")} />
          </button>
        </div>

        {/* Hall selector */}
        <div className="flex flex-wrap gap-2 mb-4">
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

        {/* Daypart selector */}
        <div className="flex gap-2 mb-6">
          {[0, 1, 2].map((dp) => (
            <button
              key={dp}
              onClick={() => setDaypart(dp)}
              className={cn(
                "px-4 py-1.5 rounded-full text-sm font-medium border transition-colors",
                daypart === dp
                  ? "bg-gray-900 text-white border-gray-900"
                  : "bg-white text-gray-700 border-gray-300 hover:bg-gray-50"
              )}
            >
              {daypartLabel(dp)}
            </button>
          ))}
        </div>

        {/* Items */}
        {loading ? (
          <div className="space-y-3">
            {[1, 2, 3, 4, 5].map((i) => (
              <div key={i} className="h-20 bg-gray-200 rounded-xl animate-pulse" />
            ))}
          </div>
        ) : items.length === 0 ? (
          <Card className="p-10 text-center text-gray-500">
            No trending items yet — interact with the menu to get things going.
          </Card>
        ) : (
          <div className="space-y-3">
            {items.map((item, idx) => (
              <Card key={item.id} className="flex items-center gap-4 p-4 bg-white">
                <span className="text-2xl font-bold text-gray-200 w-8 text-center flex-shrink-0">
                  {idx + 1}
                </span>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="font-medium text-gray-900">{item.name}</span>
                    {idx === 0 && (
                      <Badge className="bg-orange-100 text-orange-700 border-0 text-xs">
                        🔥 Hot
                      </Badge>
                    )}
                    {item.is_vegan && <span className="text-xs">🌱</span>}
                    {item.is_halal && <span className="text-xs">☪️</span>}
                  </div>
                  <div className="flex gap-3 text-sm text-gray-500">
                    {item.calories != null && <span>{item.calories} cal</span>}
                    {item.g_protein != null && <span>{item.g_protein}g protein</span>}
                    {item.station && <span>{item.station}</span>}
                  </div>
                </div>
                {item.trendingScore != null && (
                  <div className="text-right flex-shrink-0">
                    <div className="text-xs text-gray-400">score</div>
                    <div className="text-sm font-semibold text-blue-600">
                      {item.trendingScore.toFixed(1)}
                    </div>
                  </div>
                )}
              </Card>
            ))}
          </div>
        )}
      </div>
    </div>
  )
}
