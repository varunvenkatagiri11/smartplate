"use client"

import { useState } from "react"
import { useRouter } from "next/navigation"
import { Heart, MapPin } from "lucide-react"
import { Card } from "@/components/ui/card"
import { Badge } from "@/components/ui/badge"

interface DiningHall {
  id: string
  schoolId: number
  name: string
  image: string
  location: string
}

const diningHalls: DiningHall[] = [
  {
    id: "bolton",
    schoolId: 44401,
    name: "Bolton Dining Commons",
    image: "/images/bolton-dining.jpg",
    location: "East Campus",
  },
  {
    id: "oglethorpe",
    schoolId: 44402,
    name: "O-House Dining",
    image: "/images/oglethorpe-dining.jpg",
    location: "Myers Quad",
  },
  {
    id: "snelling",
    schoolId: 44403,
    name: "Snelling Dining Commons",
    image: "/images/snelling-dining.webp",
    location: "North Campus",
  },
  {
    id: "village-summit",
    schoolId: 44405,
    name: "Village Summit",
    image: "/images/village-summit.jpg",
    location: "Village",
  },
  {
    id: "niche",
    schoolId: 44404,
    name: "Niche",
    image: "/images/niche-dining.jpg",
    location: "Niche",
  },
]

export default function HallPickerPage() {
  const [favorites, setFavorites] = useState<Set<string>>(new Set())
  const router = useRouter()

  const toggleFavorite = (e: React.MouseEvent, id: string) => {
    e.stopPropagation()
    setFavorites((prev) => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  return (
    <div className="min-h-screen bg-gray-50 p-6">
      <div className="max-w-6xl mx-auto">
        <div className="mb-8 text-center">
          <h1 className="text-3xl font-bold text-gray-900 mb-2">Campus Dining</h1>
          <p className="text-gray-500">Pick a hall to see today&apos;s menu</p>
        </div>

        {/* 2x2 grid */}
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6 mb-6">
          {diningHalls.slice(0, 4).map((hall) => (
            <HallCard
              key={hall.id}
              hall={hall}
              isFavorite={favorites.has(hall.id)}
              onToggleFavorite={(e) => toggleFavorite(e, hall.id)}
              onClick={() => router.push(`/dining-hall/${hall.id}`)}
            />
          ))}
        </div>

        {/* Centered last card */}
        <div className="flex justify-center">
          <div className="w-full md:w-1/2">
            <HallCard
              hall={diningHalls[4]}
              isFavorite={favorites.has(diningHalls[4].id)}
              onToggleFavorite={(e) => toggleFavorite(e, diningHalls[4].id)}
              onClick={() => router.push(`/dining-hall/${diningHalls[4].id}`)}
            />
          </div>
        </div>
      </div>
    </div>
  )
}

function HallCard({
  hall,
  isFavorite,
  onToggleFavorite,
  onClick,
}: {
  hall: DiningHall
  isFavorite: boolean
  onToggleFavorite: (e: React.MouseEvent) => void
  onClick: () => void
}) {
  return (
    <Card
      className="relative overflow-hidden group cursor-pointer hover:shadow-lg transition-shadow"
      onClick={onClick}
    >
      <div className="relative h-64 w-full">
        <img
          src={hall.image || "/placeholder.svg"}
          alt={hall.name}
          className="w-full h-full object-cover transition-transform duration-300 group-hover:scale-105"
        />
        <div className="absolute inset-0 bg-black bg-opacity-40 transition-opacity duration-300 group-hover:bg-opacity-30" />
        <button
          onClick={onToggleFavorite}
          className="absolute top-4 right-4 p-2 rounded-full bg-white bg-opacity-20 backdrop-blur-sm hover:bg-opacity-30 transition-all"
          type="button"
        >
          <Heart
            className={`w-5 h-5 transition-colors ${
              isFavorite ? "fill-red-500 text-red-500" : "text-white"
            }`}
          />
        </button>
        <div className="absolute bottom-4 left-4 right-4">
          <h3 className="text-white text-xl font-bold drop-shadow-lg mb-1">{hall.name}</h3>
          <Badge variant="secondary" className="bg-white/20 text-white border-0 backdrop-blur-sm">
            <MapPin className="w-3 h-3 mr-1" />
            {hall.location}
          </Badge>
        </div>
      </div>
    </Card>
  )
}
