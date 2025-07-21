"use client"
import {useEffect, useState} from "react";


export default function loginPage() {
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
    const[currentPosterIndex, setPosterIndex] = useState(0);// 0 here initializes currentPosterIndex
    useEffect(() => {
        const interval = setInterval(() => {
            setPosterIndex((prevIndex) =>
                (prevIndex + 1) % posters.length);
        }, 5000)
        return () => clearInterval(interval)
    }, []);

    return (
        <div className="flex min-h-screen overflow-hidden">
            {/* Left Panel - Login Form */}
            <div className="w-full md:w-[45%] bg-black text-white flex items-center justify-center">

                {/* Login Form */}
                <div className="flex justify-center px-4 py-20">
                    {/*Login header, symbol and google OAuth links*/}
                    <div className="">
                        <h1 className="text-3xl font-bold p-8">Log In</h1>
                    </div>

                    <div>

                    </div>

                    <form className="max-w-sm mx-auto">
                        <div className="mb-5 grid gap-4">
                            <div className="grid">
                                <label htmlFor="email"
                                       className="block mb-2 text-sm font-medium text-white">Your
                                    email</label>
                                <input type="email" id="email"
                                       className="bg-gray-900  border-gray-800 text-white text-sm rounded-lg focus:ring-blue-500 focus:border-blue-500 block w-full p-2.5 placeholder-gray-300"
                                       placeholder="Username or E-mail" required/>
                            </div>
                            <div >
                                <label htmlFor="password"
                                       className="block mb-2 text-sm font-medium text-white">Your
                                    password</label>
                                <input type="password" id="password"
                                       className="bg-gray-50 border border-gray-300 text-gray-900 text-sm rounded-lg focus:ring-blue-500 focus:border-blue-500 block w-full p-2.5 dark:bg-gray-700 dark:border-gray-600 dark:placeholder-gray-400 dark:text-white dark:focus:ring-blue-500 dark:focus:border-blue-500"
                                       required/>
                            </div>
                        </div>
                        <div className="flex items-start mb-5">
                            <div className="flex items-center h-5">
                                <input id="remember" type="checkbox" value=""
                                       className="w-4 h-4 border border-gray-300 rounded-sm bg-gray-50 focus:ring-3 focus:ring-blue-300 dark:bg-gray-700 dark:border-gray-600 dark:focus:ring-blue-600 dark:ring-offset-gray-800 dark:focus:ring-offset-gray-800"
                                       required/>
                            </div>
                            <label htmlFor="remember"
                                   className="ms-2 text-sm font-medium text-gray-900 dark:text-gray-300">Remember
                                me</label>
                        </div>
                        <button type="submit"
                                className="text-white bg-blue-700 hover:bg-blue-800 focus:ring-4 focus:outline-none focus:ring-blue-300 font-medium rounded-lg text-sm w-full sm:w-auto px-5 py-2.5 text-center dark:bg-blue-600 dark:hover:bg-blue-700 dark:focus:ring-blue-800">Submit
                        </button>
                    </form>
                </div>
            </div>

            {/* Fixed Right Panel */}
            <div className="hidden md:block fixed top-0 right-0 w-[55%] h-screen overflow-hidden z-10">
                <div className="absolute inset-0 overflow-hidden">
                    {posters.map((poster, index) => (
                        <div
                            key={index}
                            className={`absolute inset-0 transition-opacity duration-1000 flex flex-col ${poster.bg} ${
                                index === currentPosterIndex ? "opacity-100" : "opacity-0"
                            }`}
                        >
                            {/* Image container */}
                            <div className="flex-grow flex items-center justify-center overflow-hidden p-4">
                                <div className="w-full h-full max-w-full max-h-full flex items-center justify-center">
                                    <img
                                        src={poster.src}
                                        alt={poster.title + " - " + poster.subtitle}
                                        className="max-w-full max-h-full object-contain rounded-lg"
                                    />
                                </div>
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
        </div>
    );
}