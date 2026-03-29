/* eslint-disable @next/next/no-img-element */

"use client"
import React, {useEffect, useState} from "react";
import { useRouter } from 'next/navigation';
import {login} from "@/lib/api";

export default function LoginPage() {
    const posters = [
        {
            title: "Interstellar",
            subtitle: "Christopher Nolan · 2014",
            src: "/posters/interstellar.jpg",
            bg: "bg-gradient-to-br from-amber-700 via-gray-770 to-black/40"
        },
        {
            title: "Dune: Part Two",
            subtitle: "Denis Villeneuve · 2024",
            src: "/posters/Dune.jpeg",
            bg: "bg-gradient-to-br from-orange-900 via-amber-800 to-slate-900"
        },
        {
            title: "Oppenheimer",
            subtitle: "Christopher Nolan · 2023",
            src: "/posters/oppenheimer.jpg",
            bg: "bg-gradient-to-br from-orange-900 via-amber-500 to-zinc-950"
        },
        {
            title: "The Worst Person in the World",
            subtitle: "Joachim Trier · 2022",
            src: "/posters/worst.jpg",
            bg: "bg-gradient-to-br from-rose-500 via-purple-900 to-gray-900"
        }
    ];
    const [currentPosterIndex, setPosterIndex] = useState(0);// 0 here initializes currentPosterIndex
    const [visible, setVisible] = useState(false);
    const [formData, setFormData] = useState({loginInfo: '', password: ''})
    const [error, setError]= useState('')
    const router = useRouter()
    useEffect(() => {
        const interval = setInterval(() => {
            setPosterIndex((prevIndex) =>
                (prevIndex + 1) % posters.length);
        }, 5000)
        return () => clearInterval(interval)
    }, [posters.length]);

    useEffect(() => {
        setError('')
    }, [formData.loginInfo, formData.password]);

    const isFormComplete = () => {
        return formData.password.trim() !== '' && formData.loginInfo.trim() !== '';
    }

    const handleSubmit = async (e: React.FormEvent) => {
        e.preventDefault() // prevents page reload when form is submitted
        try {
            await login(formData.loginInfo, formData.password);
            router.push("/homepage")
        } catch {
            setError("Invalid login credentials. The username and password you entered is not valid.");
        }
    }

    return (
        <div className="flex min-h-screen overflow-hidden">
            {/* Left Panel - Login Form */}
            <div className="w-full md:w-[50%] bg-black text-white flex items-center justify-center">
                <div className="flex justify-center px-4 py-15 w-full">
                    {/* Login Form */}
                    <div className="flex flex-col justify-center px-4 py-20 gap-4 w-full max-w-md">
                        {/* Logo and header */}
                        <div className="flex flex-col items-start">
                            <svg className="-ml-7" width="130" height="130" viewBox="0 0 256 256" xmlns="http://www.w3.org/2000/svg">
                                <defs>
                                    <linearGradient id="meltGradient" x1="0%" y1="0%" x2="100%" y2="100%">
                                        <stop offset="0%" stopColor="#f59e0b" />
                                        <stop offset="35%" stopColor="#f97316" />
                                        <stop offset="75%" stopColor="#fb7185" />
                                    </linearGradient>
                                </defs>
                                <path fill="url(#meltGradient)"
                                      d="M 65 42 L 56 55 L 56 165 L 59 172 L 68 179 L 78 180 L 81 199 L 87 203 L 95 203 L 101 198 L 103 193 L 103 158 L 107 156 L 110 160 L 110 178 L 112 182 L 118 186 L 129 184 L 133 178 L 133 156 L 138 153
                                        L 140 155 L 141 184 L 148 190 L 160 188 L 164 181 L 164 133 L 184 118 L 187 110 L 184 100 L 178 94 L 150 79 L 143 79 L 135 87 L 133 109 L 128 107 L 127 97 L 122 91 L 113 90 L 107 93 L 104 98
                                        L 104 121 L 99 123 L 97 121 L 97 103 L 95 99 L 89 95 L 75 96 L 77 103 L 86 103 L 88 105 L 89 124 L 97 132 L 104 132 L 110 128 L 112 124 L 112 101 L 114 99 L 119 101 L 119 108 L 124 116
                                        L 129 118 L 138 116 L 143 109 L 143 90 L 145 88 L 149 88 L 174 102 L 178 108 L 178 111 L 165 122 L 162 114 L 158 114 L 155 117 L 154 181 L 151 182 L 149 180 L 149 155 L 144 146 L 133 144 L 128 147
                                        L 125 152 L 125 174 L 123 177 L 118 175 L 118 156 L 116 152 L 111 148 L 102 148 L 97 151 L 94 157 L 94 193 L 90 195 L 87 192 L 87 162 L 81 161 L 78 171 L 71 171 L 65 165 L 65 55 L 71 49 L 78 49 L 126 76 L 131 76 L 133 71 L 81 41 L 71 40 Z"/>
                            </svg>
                            <h1 className="text-3xl font-bold m-0">Log In</h1>
                        </div>

                        <form className="w-full" onSubmit={handleSubmit}>
                            <div className="mb-5 grid gap-6 w-full">
                                {/* Email Field */}
                                <div className="mt-10 grid w-full">
                                    <div className="relative w-full">
                                        <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                                            <svg
                                                xmlns="http://www.w3.org/2000/svg"
                                                viewBox="0 0 24 24"
                                                fill="none"
                                                className="h-5 w-5 text-gray-400">
                                                <path
                                                    d="M1 4.5 L12 14.5 L23 4.5 M1 4.5 H23V19H1V4.5Z"
                                                    stroke="#A19F9F"
                                                    strokeWidth="1.6"
                                                    strokeLinejoin="round"
                                                />
                                            </svg>
                                        </div>
                                        <input
                                            className="flex h-13 w-full rounded-lg border border-gray-500 bg-transparent pl-10 py-2 text-base transition-colors
                                                       focus-visible:outline-none focus-visible:ring-2 hover:border-gray-300 focus:border-white"
                                            id="emailOrUsername"
                                            type="text"
                                            placeholder="E-mail / Username"
                                            autoCapitalize="none"
                                            autoComplete="email"
                                            autoCorrect="off"
                                            value={formData.loginInfo}
                                            onChange={(e)=> {
                                                setFormData({...formData, loginInfo: e.target.value}) // copy content from the formData object and change the value of loginInfo. ... is the spread operator.
                                            }}
                                            required
                                        />
                                    </div>
                                </div>

                                {/*Password Field*/}
                                <div className="grid w-full">
                                    <div className="relative w-full">
                                        <div className="absolute inset-y-0 left-0 pl-3 flex items-center pointer-events-none">
                                            <svg
                                                xmlns="http://www.w3.org/2000/svg"
                                                viewBox="0 0 24 24"
                                                fill="none"
                                                className="h-5 w-5 text-gray-400"
                                            >
                                                <path
                                                    d="M12.9819 14.7816C12.9819 14.2394 12.5423 13.7998 12.0001 13.7998C11.4578 13.7998 11.0182 14.2394 11.0182 14.7816V17.0289C11.0182 17.5711 11.4578 18.0107
                                                    12.0001 18.0107C12.5423 18.0107 12.9819 17.5711 12.9819 17.0289V14.7816Z"
                                                    stroke="#A19F9F"
                                                    strokeWidth="1.6"
                                                    strokeLinejoin="round"
                                                />
                                                <path fillRule="evenodd" clipRule="evenodd" d="M7.00012 6.51953V9.52051H6.42405C4.54628 9.52051 3.02405 11.0427 3.02405 12.9205V19.1205C3.02405 20.9983
                                                4.54628 22.5205 6.42405 22.5205H17.576C19.4538 22.5205 20.976 20.9983 20.976 19.1205V12.9205C20.976 11.0427 19.4538 9.52051 17.576 9.52051H17.0001V6.51953C17.0001
                                                3.75811 14.7615 1.51953 12.0001 1.51953C9.2387 1.51953 7.00012 3.75811 7.00012 6.51953ZM12.0001 3.51953C10.3433 3.51953 9.00012 4.86268 9.00012 6.51953V9.52051H15.0001V6.51953C15.0001
                                                4.86268 13.657 3.51953 12.0001 3.51953ZM17.576 11.5205H6.42405C5.65085 11.5205 5.02405 12.1473 5.02405 12.9205V19.1205C5.02405 19.8937 5.65085 20.5205 6.42405 20.5205H17.576C18.3492
                                                20.5205 18.976 19.8937 18.976 19.1205V12.9205C18.976 12.1473 18.3492 11.5205 17.576 11.5205Z" fill="currentColor"></path>
                                            </svg>
                                        </div>
                                        <input
                                            type={visible ? "text" : "password"}
                                            className="flex h-13 w-full rounded-lg border border-gray-500 bg-transparent pl-10 pr-10 py-2 text-base transition-colors
                                                       focus-visible:outline-none focus-visible:ring-2 hover:border-gray-300 focus:border-white"
                                            id="password"
                                            placeholder="Password"
                                            autoCapitalize="none"
                                            autoComplete="current-password"
                                            value={formData.password}
                                            onChange={(e) => setFormData({...formData, password: e.target.value})}
                                            required
                                        />
                                        {/* Password visibility toggle */}
                                        <button
                                            type="button"
                                            className="absolute inset-y-0 right-0 pr-3 flex items-center text-gray-400 hover:text-gray-300 focus-visible:outline-none focus-visible:ring-2"
                                            onClick={() => setVisible((prev) => !prev)}
                                        >
                                            {visible ? (
                                                // Eye open icon - complete eye shape
                                                <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                                                    <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                                                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5c-5 0-9.27 3.11-11 7.5 1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5z" />
                                                </svg>
                                            ) : (
                                                // Eye closed icon - same eye shape with added slash
                                                <svg xmlns="http://www.w3.org/2000/svg" className="h-5 w-5" fill="none" viewBox="0 0 24 24" stroke="currentColor" strokeWidth={2}>
                                                    <path strokeLinecap="round" strokeLinejoin="round" d="M15 12a3 3 0 11-6 0 3 3 0 016 0z" />
                                                    <path strokeLinecap="round" strokeLinejoin="round" d="M12 4.5c-5 0-9.27 3.11-11 7.5 1.73 4.39 6 7.5 11 7.5s9.27-3.11 11-7.5c-1.73-4.39-6-7.5-11-7.5z" />
                                                    <path strokeLinecap="round" strokeLinejoin="round" d="M3 3l18 18" />
                                                </svg>
                                            )}
                                        </button>
                                    </div>
                                    <div className="mt-3 mb-1">
                                        {error && <p className="text-red-500">{error}</p>}
                                    </div>
                                    <div className="flex flex-row">
                                        <div className="mt-4 flex justify-between items-center mb-2">
                                            <a
                                                className="text-md text-gray-400 hover:text-gray-200 transition-colors focus-visible:outline-none focus-visible:ring-2"
                                                href="/password-reset"
                                            >
                                                Forgot password?
                                            </a>
                                        </div>
                                        <div className="pl-38 mt-4 flex justify-between items-center mb-2">
                                            <a
                                                className="text-md text-gray-300 hover:text-gray-50 transition-colors focus-visible:outline-none focus-visible:ring-2"
                                                href="/register"
                                            >
                                                Create an account.
                                            </a>
                                        </div>
                                    </div>
                                </div>
                            </div>

                            <button
                                type="submit"
                                disabled={!isFormComplete}
                                className="text-black bg-white hover:bg-gray-300 focus:outline-none font-medium rounded-lg text-base w-full py-3.5 text-center transition-colors">
                                Log In
                            </button>

                        </form>
                    </div>
                </div>
            </div>

            {/* Fixed Right Panel, think of the frame analogy. */}
            <div className="hidden md:block fixed inset-y-0 right-0 w-[50%] h-screen overflow-hidden z-10"> {/*fixed helps keep it in place*/}
                {posters.map((poster, index) => (
                    <div
                        key={index}
                        className={`absolute inset-0 transition-opacity duration-1000 flex flex-col ${poster.bg} ${
                            index === currentPosterIndex ? "opacity-100" : "opacity-0"
                        }`}
                    >
                        {/* Image container */}
                        <div className="flex-grow flex items-center justify-center overflow-hidden p-8">
                            <img
                                src={poster.src}
                                alt={poster.title + " - " + poster.subtitle}
                                className="max-w-full max-h-full object-contain rounded-xl"
                            />
                        </div>

                        {/* Indicators */}
                        <div className="w-full flex justify-center pb-3 pt-1">
                            {posters.map((_, i) => (
                                <div
                                    key={i}
                                    className={`w-2 h-2 rounded-full transition-all duration-300 mx-1 ${
                                        i === currentPosterIndex ? "bg-white w-6" : "bg-white/30"
                                    }`}
                                />
                            ))}
                        </div>

                        {/* Gradient overlay */}
                        <div className="absolute inset-0 pointer-events-none">
                            <div className="absolute inset-0 bg-gradient-to-t from-black/45 to-transparent" />
                        </div>
                    </div>
                ))}
            </div>
        </div>
    );
}
